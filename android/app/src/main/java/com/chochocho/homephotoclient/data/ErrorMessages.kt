package com.chochocho.homephotoclient.data

import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 예외를 사용자에게 보여줄 한국어 메시지로 변환한다. */
fun Throwable.toFriendlyMessage(): String = when (this) {
    is UnknownHostException ->
        "서버 주소를 찾을 수 없습니다. 설정에서 서버 주소를 확인해주세요."
    is ConnectException, is SocketTimeoutException ->
        "서버에 연결할 수 없습니다. 서버가 켜져 있고 같은 Wi-Fi인지 확인해주세요."
    is HttpException -> when (code()) {
        401 -> "API 키가 올바르지 않습니다. 설정에서 확인해주세요."
        else -> "서버 오류가 발생했습니다 (HTTP ${code()})"
    }
    is IOException ->
        "네트워크 오류가 발생했습니다. 연결 상태를 확인해주세요."
    else -> message ?: javaClass.simpleName
}
