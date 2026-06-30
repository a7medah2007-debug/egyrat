package com.example.target

import com.example.shared.CryptoUtils
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.SecretKey
import kotlin.concurrent.thread

class TargetSocketClient(
    private val host: String,
    private val port: Int,
    private val secretKey: SecretKey,
    private val onCommandReceived: (String) -> Unit,
    private val onConnectionStatusChanged: (Boolean) -> Unit
) {
    private var socket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null
    private var isRunning = false
    private var isConnected = false

    fun connect() {
        if (isRunning) return
        isRunning = true
        
        thread {
            while (isRunning) {
                try {
                    val tempSocket = Socket()
                    tempSocket.connect(InetSocketAddress(host, port), 5000)
                    
                    socket = tempSocket
                    val dataInputStream = DataInputStream(tempSocket.getInputStream())
                    dataOutputStream = DataOutputStream(tempSocket.getOutputStream())
                    
                    isConnected = true
                    onConnectionStatusChanged(true)
                    
                    handleServerCommunication(dataInputStream)
                } catch (e: Exception) {
                    cleanup()
                    onConnectionStatusChanged(false)
                    try {
                        Thread.sleep(5000)
                    } catch (ie: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun handleServerCommunication(inputStream: DataInputStream) {
        val currentSocket = socket ?: return
        try {
            while (isRunning && currentSocket.isConnected && !currentSocket.isClosed) {
                val length = inputStream.readInt()
                if (length <= 0) break
                
                val encryptedBytes = ByteArray(length)
                inputStream.readFully(encryptedBytes)
                
                val decryptedBytes = CryptoUtils.decrypt(encryptedBytes, secretKey)
                val decryptedCommand = String(decryptedBytes, Charsets.UTF_8)
                
                onCommandReceived(decryptedCommand)
            }
        } catch (e: IOException) {
            // Socket closed
        } finally {
            cleanup()
            onConnectionStatusChanged(false)
        }
    }

    fun sendResult(result: String): Boolean {
        val outStream = dataOutputStream ?: return false
        val currentSocket = socket ?: return false
        
        if (!currentSocket.isConnected || currentSocket.isClosed) return false
        
        return try {
            val plaintextBytes = result.toByteArray(Charsets.UTF_8)
            val encryptedBytes = CryptoUtils.encrypt(plaintextBytes, secretKey)
            
            synchronized(outStream) {
                outStream.writeInt(encryptedBytes.size)
                outStream.write(encryptedBytes)
                outStream.flush()
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun cleanup() {
        isConnected = false
        try {
            socket?.close()
        } catch (e: IOException) {
            // Ignore
        }
        socket = null
        dataOutputStream = null
    }

    fun disconnect() {
        isRunning = false
        cleanup()
    }
}
