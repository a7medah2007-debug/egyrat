package com.example.controller

import com.example.shared.CryptoUtils
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.SecretKey
import kotlin.concurrent.thread

class AdminSocketServer(
    private val port: Int,
    private val secretKey: SecretKey,
    private val onMessageReceived: (String) -> Unit,
    private val onClientConnected: (String) -> Unit,
    private val onClientDisconnected: () -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        
        thread {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    clientSocket = socket
                    val dataInputStream = DataInputStream(socket.getInputStream())
                    dataOutputStream = DataOutputStream(socket.getOutputStream())
                    
                    onClientConnected(socket.inetAddress.hostAddress)
                    handleClientCommunication(dataInputStream)
                }
            } catch (e: IOException) {
                if (isRunning) {
                    System.err.println("Server error: ${e.message}")
                }
            } finally {
                stop()
            }
        }
    }

    private fun handleClientCommunication(inputStream: DataInputStream) {
        try {
            while (isRunning && clientSocket?.isConnected == true) {
                val length = inputStream.readInt()
                if (length <= 0) break
                
                val encryptedBytes = ByteArray(length)
                inputStream.readFully(encryptedBytes)
                
                val decryptedBytes = CryptoUtils.decrypt(encryptedBytes, secretKey)
                val decryptedMessage = String(decryptedBytes, Charsets.UTF_8)
                
                onMessageReceived(decryptedMessage)
            }
        } catch (e: IOException) {
            println("Client disconnected: ${e.message}")
        } finally {
            cleanupClient()
            onClientDisconnected()
        }
    }

    fun sendMessage(message: String): Boolean {
        val outStream = dataOutputStream ?: return false
        val socket = clientSocket ?: return false
        
        if (!socket.isConnected || socket.isClosed) return false
        
        return try {
            val plaintextBytes = message.toByteArray(Charsets.UTF_8)
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

    private fun cleanupClient() {
        try {
            clientSocket?.close()
        } catch (e: IOException) {
            // Ignore
        }
        clientSocket = null
        dataOutputStream = null
    }

    fun stop() {
        isRunning = false
        cleanupClient()
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // Ignore
        }
        serverSocket = null
    }
}
