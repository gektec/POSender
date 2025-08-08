package com.example.posender

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    // --- 音频配置 (必须与ROS端匹配) ---
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // --- UI & 录音状态 ---
    private lateinit var etServerIp: EditText
    private lateinit var btnStartRecord: Button
    private lateinit var btnStopAndSend: Button
    private lateinit var tvStatus: TextView
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private lateinit var recordingThread: Thread
    private val recordedAudioStream = ByteArrayOutputStream()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private const val SERVER_PORT = 2052 // 确保与Linux服务器端口一致
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServerIp = findViewById(R.id.etServerIp)
        btnStartRecord = findViewById(R.id.btnStartRecord)
        btnStopAndSend = findViewById(R.id.btnStopAndSend)
        tvStatus = findViewById(R.id.tvStatus)

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

    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        audioRecord?.startRecording()
        isRecording = true
        recordedAudioStream.reset() // 清空上一次的录音

        // 开启新线程进行录音
        recordingThread = thread(start = true) {
            val data = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    recordedAudioStream.write(data, 0, read)
                }
            }
        }

        // 更新UI
        runOnUiThread {
            tvStatus.text = "状态：正在录音..."
            btnStartRecord.isEnabled = false
            btnStopAndSend.isEnabled = true
        }
    }

    private fun stopAndSend() {
        if (!isRecording) return

        // 停止录音
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingThread.join() // 等待录音线程结束

        // 更新UI
        runOnUiThread {
            tvStatus.text = "状态：准备发送..."
            btnStopAndSend.isEnabled = false // 防止重复点击
        }

        // 在后台线程中发送数据
        thread(start = true) {
            val serverIp = etServerIp.text.toString()
            if (serverIp.isBlank()) {
                runOnUiThread { Toast.makeText(this, "IP地址不能为空", Toast.LENGTH_SHORT).show() }
                return@thread
            }

            try {
                runOnUiThread { tvStatus.text = "状态：正在连接到 $serverIp..." }
                val socket = Socket(serverIp, SERVER_PORT)
                runOnUiThread { tvStatus.text = "状态：已连接，正在发送数据..." }

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
                // 恢复按钮状态
                runOnUiThread {
                    btnStartRecord.isEnabled = true
                    btnStopAndSend.isEnabled = false
                }
            }
        }
    }

    // --- 权限管理 ---
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
}