package com.tans.tfiletransporter.netty.handlers

import com.tans.tfiletransporter.netty.ByteArrayPool
import com.tans.tfiletransporter.netty.PackageDataWithAddress
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.socket.DatagramPacket

class PckAddrDataToDatagramDataEncoder(
    private val byteArrayPool: ByteArrayPool
) :  ChannelOutboundHandlerAdapter() {

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg is PackageDataWithAddress) {
            val buffer = ctx.alloc().buffer()
            buffer.writeInt(msg.data.type)
            buffer.writeLong(msg.data.messageId)
            val bytes = msg.data.body.value.value
            val bodySize = msg.data.body.readSize
            buffer.writeBytes(bytes, 0, bodySize)
            super.write(ctx, DatagramPacket(buffer, msg.receiverAddress, msg.senderAddress), promise)
            byteArrayPool.put(msg.data.body.value)
        }
    }

}