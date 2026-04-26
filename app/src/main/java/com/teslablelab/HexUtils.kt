package com.teslablelab

internal fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
