package com.gradwahl.rs254.io

class IsaacCipher(seed: IntArray) {
    private var count = 0
    private val rsl = IntArray(256)
    private val mem = IntArray(256)
    private var a = 0
    private var b = 0
    private var c = 0

    init {
        System.arraycopy(seed, 0, rsl, 0, minOf(seed.size, rsl.size))
        initState()
    }

    fun nextInt(): Int {
        val previous = count
        count--
        if (previous == 0) {
            isaac()
            count = 255
        }
        return rsl[count]
    }

    private fun mix(x: IntArray) {
        x[0] = x[0] xor (x[1] shl 11); x[3] += x[0]; x[1] += x[2]
        x[1] = x[1] xor (x[2] ushr 2); x[4] += x[1]; x[2] += x[3]
        x[2] = x[2] xor (x[3] shl 8); x[5] += x[2]; x[3] += x[4]
        x[3] = x[3] xor (x[4] ushr 16); x[6] += x[3]; x[4] += x[5]
        x[4] = x[4] xor (x[5] shl 10); x[7] += x[4]; x[5] += x[6]
        x[5] = x[5] xor (x[6] ushr 4); x[0] += x[5]; x[6] += x[7]
        x[6] = x[6] xor (x[7] shl 8); x[1] += x[6]; x[7] += x[0]
        x[7] = x[7] xor (x[0] ushr 9); x[2] += x[7]; x[0] += x[1]
    }

    private fun initState() {
        val x = IntArray(8) { 0x9e3779b9L.toInt() }
        repeat(4) { mix(x) }

        var i = 0
        while (i < 256) {
            for (j in 0 until 8) x[j] += rsl[i + j]
            mix(x)
            System.arraycopy(x, 0, mem, i, 8)
            i += 8
        }

        i = 0
        while (i < 256) {
            for (j in 0 until 8) x[j] += mem[i + j]
            mix(x)
            System.arraycopy(x, 0, mem, i, 8)
            i += 8
        }

        isaac()
        count = 256
    }

    private fun isaac() {
        c++
        b += c
        for (i in 0 until 256) {
            val x = mem[i]
            when (i and 3) {
                0 -> a = a xor (a shl 13)
                1 -> a = a xor (a ushr 6)
                2 -> a = a xor (a shl 2)
                3 -> a = a xor (a ushr 16)
            }
            a += mem[(i + 128) and 0xff]
            val y = mem[(x ushr 2) and 0xff] + a + b
            mem[i] = y
            b = mem[(y ushr 10) and 0xff] + x
            rsl[i] = b
        }
    }
}
