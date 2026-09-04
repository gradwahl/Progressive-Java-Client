package com.gradwahl.rs254.io

class Protocol private constructor() {
    class Client private constructor() {
        companion object {
            const val NO_TIMEOUT = 239
            const val MOVE_GAMECLICK = 6
            const val MESSAGE_PUBLIC = 83
            const val CLIENT_CHEAT = 86
            const val FRIENDLIST_ADD = 9
            const val FRIENDLIST_DEL = 84
            const val CHAT_SETMODE = 129
        }
    }

    class Server private constructor() {
        companion object {
            const val LOGOUT = 21
            const val MESSAGE_GAME = 73
            const val PLAYER_INFO = 87
            const val NPC_INFO = 123
            const val REBUILD_NORMAL = 209

            @JvmField
            val SIZES = intArrayOf(
                6, 0, 0, 4, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 4, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 3, 5, 0, 6, -2, 0, 4, 0,
                0, 0, 0, 0, 0, 15, 4, 0, 0, -2, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 6, 0, 0, 1, 0, -1, -2, 0, -2,
                6, 0, 0, 0, 0, 0, 4, 0, 0, -1, 0, 1, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 2, 0, -2, 2, 0, 0, 3, 0, 0, 1, 4, 0, 0,
                7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9, 0, 0, 6, 3,
                0, 0, 0, 0, 5, 0, 0, -2, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0,
                0, 0, 6, 0, 1, 0, 0, 2, 0, 2, 0, 0, 10, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 2, 0, 2, 0, 2, 2, 0, 0, 0, 2, 0,
                -2, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 2,
                0, 0, 0, 0, 0, 0, 0, 0, 6, 2, 0, 0, 0, 0, 0, 0, -1, 0,
                0, 0, 0, 4, 0, 4, 0, 3, 0, 0, 0, 0, 14, 0, 0, 0, 6, 0,
                0, 4, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0,
                4, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 1, 0
            )
        }
    }
}
