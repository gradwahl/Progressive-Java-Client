package com.gradwahl.rs254.io

import java.math.BigInteger
import java.util.zip.CRC32

class PacketBuffer {
    @JvmField
    val data: ByteArray

    @JvmField
    var pos: Int = 0

    @JvmField
    var random: IsaacCipher? = null

    constructor(size: Int) {
        data = ByteArray(size)
    }

    constructor(data: ByteArray) {
        this.data = data
    }

    fun g1(): Int {
        val value = data[pos].toInt() and 0xff
        pos++
        return value
    }

    fun g4(): Int =
        (g1() shl 24) or
            (g1() shl 16) or
            (g1() shl 8) or
            g1()

    fun g8(): Long = (g4().toLong() shl 32) or (g4().toLong() and 0xffffffffL)

    fun p1(value: Int) {
        data[pos++] = value.toByte()
    }

    fun p2(value: Int) {
        data[pos++] = (value ushr 8).toByte()
        data[pos++] = value.toByte()
    }

    fun p4(value: Int) {
        data[pos++] = (value ushr 24).toByte()
        data[pos++] = (value ushr 16).toByte()
        data[pos++] = (value ushr 8).toByte()
        data[pos++] = value.toByte()
    }

    fun pjstr(value: String) {
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        pdata(bytes, 0, bytes.size)
        p1(10)
    }

    fun pdata(src: ByteArray, off: Int, len: Int) {
        System.arraycopy(src, off, data, pos, len)
        pos += len
    }

    fun pIsaac(opcode: Int) {
        val cipher = random ?: throw IllegalStateException("ISAAC is not initialised")
        p1((opcode + cipher.nextInt()) and 0xff)
    }

    fun bytes(): ByteArray = data.copyOf(pos)

    fun rsaenc(modulus: BigInteger, exponent: BigInteger) {
        val raw = data.copyOf(pos)
        val rawInt = BigInteger(1, raw)
        var encrypted = rawInt.modPow(exponent, modulus).toByteArray()
        if (encrypted.isNotEmpty() && encrypted[0].toInt() == 0) {
            encrypted = encrypted.copyOfRange(1, encrypted.size)
        }
        pos = 0
        p1(encrypted.size)
        pdata(encrypted, 0, encrypted.size)
    }

    companion object {
        @JvmStatic
        fun crc32(src: ByteArray, off: Int, len: Int): Int {
            val crc = CRC32()
            crc.update(src, off, len)
            return crc.value.toInt()
        }
    }
}
