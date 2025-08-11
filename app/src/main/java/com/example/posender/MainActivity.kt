package com.example.posender

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.posender.ui.Joystick1DView
import com.example.posender.ui.Joystick2DView
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // 写死的服务器 IP（与局域网接收端一致）
    companion object {
        private const val SERVER_IP = "192.168.1.33"
        private const val AUDIO_PORT = 2052
        private const val TWIST_PORT = 2053
        private const val PERMISSION_REQUEST_CODE = 101

        // 发送频率
        private const val TWIST_HZ = 10
        private const val TWIST_PERIOD_MS = 100L

        // 最大速度（可按需调整）
        private const val MAX_VXY = 0.5f    // m/s
        private const val MAX_VZ = 0.5f     // m/s
        private const val MAX_RPY = 15.0f    // deg/s
    }

    // 音频配置
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // UI
    private lateinit var btnStartRecord: Button
    private lateinit var btnStopAndSend: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCtrlStatus: TextView

    private lateinit var jsXY: Joystick2DView
    private lateinit var jsZ: Joystick1DView
    private lateinit var jsPitchYaw: Joystick2DView
    private lateinit var jsRoll: Joystick1DView

    // 录音状态
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private lateinit var recordingThread: Thread
    private val recordedAudioStream = ByteArrayOutputStream()

    // Twist 状态（线程间共享）
    private val lock = Any()
    private var vx = 0f
    private var vy = 0f
    private var vz = 0f
    private var roll = 0f
    private var pitch = 0f
    private var yaw = 0f

    // Twist 发送线程
    private var twistThread: Thread? = null
    private val twistRunning = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStartRecord = findViewById(R.id.btnStartRecord)
        btnStopAndSend = findViewById(R.id.btnStopAndSend)
        tvStatus = findViewById(R.id.tvStatus)
        tvCtrlStatus = findViewById(R.id.tvCtrlStatus)

        jsXY = findViewById(R.id.jsXY)
        jsZ = findViewById(R.id.jsZ)
        jsPitchYaw = findViewById(R.id.jsPitchYaw)
        jsRoll = findViewById(R.id.jsRoll)

        // 配置 1D 摇杆方向
        jsZ.orientation = Joystick1DView.Orientation.VERTICAL
        jsRoll.orientation = Joystick1DView.Orientation.VERTICAL

        // 监听摇杆变化并映射到速度（单位 m/s, rad/s）
        jsXY.listener = object : Joystick2DView.Listener {
            override fun onChanged(x: Float, y: Float, touching: Boolean) {
                synchronized(lock) {
                    // x 为前 -> vx；y 为右 -> vy
                    vx = x * MAX_VXY
                    vy = y * MAX_VXY
                }
            }
        }
        jsZ.listener = object : Joystick1DView.Listener {
            override fun onChanged(v: Float, touching: Boolean) {
                synchronized(lock) { vz = v * MAX_VZ }
            }
        }
        jsPitchYaw.listener = object : Joystick2DView.Listener {
            override fun onChanged(x: Float, y: Float, touching: Boolean) {
                synchronized(lock) {
                    // 上推为正 pitch，右推为正 yaw
                    pitch = y * MAX_RPY
                    yaw = x * MAX_RPY
                }
            }
        }
        jsRoll.listener = object : Joystick1DView.Listener {
            override fun onChanged(v: Float, touching: Boolean) {
                synchronized(lock) { roll = v * MAX_RPY }
            }
        }

        // 录音按钮
        btnStartRecord.setOnClickListener {
            if (checkPermissions()) {
                startRecording()
            } else {
                requestPermissions()
            }
        }
        btnStopAndSend.setOnClickListener {
            stopAndSend()
        }
    }

    override fun onStart() {
        super.onStart()
        startTwistSender()
    }

    override fun onStop() {
        super.onStop()
        stopTwistSender()
    }

    // 录音相关（与原逻辑一致，仅 IP 写死）
    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        audioRecord?.startRecording()
        isRecording = true
        recordedAudioStream.reset()

        recordingThread = thread(start = true, name = "AudioRecordThread") {
            val data = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    recordedAudioStream.write(data, 0, read)
                }
            }
        }

        runOnUiThread {
            tvStatus.text = "状态：正在录音..."
            btnStartRecord.isEnabled = false
            btnStopAndSend.isEnabled = true
        }
    }

    private fun stopAndSend() {
        if (!isRecording) return
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingThread.join()

        runOnUiThread {
            tvStatus.text = "状态：准备发送..."
            btnStopAndSend.isEnabled = false
        }

        thread(start = true, name = "AudioSendThread") {
            try {
                runOnUiThread { tvStatus.text = "状态：正在连接到 $SERVER_IP..." }
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(SERVER_IP, AUDIO_PORT), 3000)

                val outputStream: OutputStream = socket.getOutputStream()
                outputStream.write(recordedAudioStream.toByteArray())
                outputStream.flush()
                socket.close()

                runOnUiThread {
                    tvStatus.text = "状态：发送完成！"
                    Toast.makeText(this, "音频发送成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvStatus.text = "状态：发送失败"
                    Toast.makeText(this, "发送失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    btnStartRecord.isEnabled = true
                    btnStopAndSend.isEnabled = false
                }
            }
        }
    }

    // Twist 发送
    private fun startTwistSender() {
        if (twistRunning.getAndSet(true)) return
        twistThread = thread(start = true, name = "TwistSender") {
            var seq = 0
            var writer: BufferedWriter? = null
            var socket: Socket? = null

            fun closeSocket() {
                try { writer?.flush() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
                writer = null
                socket = null
            }

            fun setCtrlStatus(text: String) {
                runOnUiThread { tvCtrlStatus.text = text}
            }

            while (twistRunning.get()) {
                try {
                    // 连接
                    if (socket == null) {
                        setCtrlStatus("控制：正在连接 $SERVER_IP:$TWIST_PORT ...")
                        val s = Socket()
                        s.tcpNoDelay = true
                        s.keepAlive = true
                        s.connect(InetSocketAddress(SERVER_IP, TWIST_PORT), 3000)
                        socket = s
                        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
                        // hello
                        val hello = mapOf(
                            "type" to "hello",
                            "client_id" to ("android-" + (Build.MODEL ?: "device")),
                            "version" to 1
                        ).toJsonLine()
                        writer!!.write(hello); writer!!.flush()
                        setCtrlStatus("控制：已连接")
                    }

                    // 周期发送
                    val nowSec = System.currentTimeMillis() / 1000.0
                    val (lx, ly, lz, lr, lp, lyaw) = synchronized(lock) { Sextuple(vx, vy, vz, roll, pitch, yaw) }
                    val msg = mapOf(
                        "type" to "twist",
                        "seq" to seq,
                        "ts" to nowSec,
                        "vx" to round3(lx),
                        "vy" to round3(ly),
                        "vz" to round3(lz),
                        "roll" to round3(lr),
                        "pitch" to round3(lp),
                        "yaw" to round3(lyaw)
                    ).toJsonLine()
                    writer!!.write(msg)
                    writer!!.flush()
                    seq++

                    Thread.sleep(TWIST_PERIOD_MS)
                } catch (e: Exception) {
                    e.printStackTrace()
                    setCtrlStatus("控制：连接中断，重试中...")
                    // 发送失败/断开，尝试关闭并重连
                    try {
                        // 尝试发送 bye（如果还能写）
                        writer?.let {
                            val bye = mapOf("type" to "bye", "reason" to "error").toJsonLine()
                            try { it.write(bye); it.flush() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                    closeSocket()
                    // 稍等再重连
                    try { Thread.sleep(1000) } catch (_: Exception) {}
                }
            }

            // 停止时发送 bye 并关闭
            try {
                writer?.write(mapOf("type" to "bye", "reason" to "app_stopped").toJsonLine())
                writer?.flush()
            } catch (_: Exception) {}
            closeSocket()
            setCtrlStatus("控制：未连接")
        }
    }

    private fun stopTwistSender() {
        twistRunning.set(false)
        twistThread?.join(1500)
        twistThread = null
    }

    // 工具
    private fun round3(v: Float): Float = (v * 1000f).roundToInt() / 1000f

    private fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "权限已获取", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要录音权限才能使用此功能", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 小工具：构造 JSON 行（简单键值，值为数字/字符串）
    private fun Map<String, Any>.toJsonLine(): String {
        val b = StringBuilder()
        b.append('{')
        var first = true
        for ((k, v) in this) {
            if (!first) b.append(',')
            first = false
            b.append('"').append(k).append('"').append(':')
            when (v) {
                is Number -> b.append(v.toString())
                else -> {
                    val s = v.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                    b.append('"').append(s).append('"')
                }
            }
        }
        b.append('}')
        b.append('\n')
        return b.toString()
    }

    data class Sextuple<A,B,C,D,E,F>(val a:A,val b:B,val c:C,val d:D,val e:E,val f:F)
    private fun <A,B,C,D,E,F> sextuple(a:A,b:B,c:C,d:D,e:E,f:F) = Sextuple(a,b,c,d,e,f)
}