package com.chochocho.homephotoclient.data

import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.TimeUnit

data class MonthDto(val yearMonth: String, val count: Long)
data class AssetDto(
    val id: Long,
    val hash: String,
    val mediaType: String?,
    val originalFilename: String?,
    val takenAt: String?,
    val takenAtSource: String?,
    val yearMonth: String?,
    val width: Int?,
    val height: Int?,
)
data class AssetPageDto(val items: List<AssetDto>, val nextCursor: String?)
data class CheckRequest(val hashes: List<String>)
data class CheckResponse(
    val missing: List<String>,
    /** 서버에서 삭제된(스킵 대상) 해시들 */
    val deleted: List<String>?,
)
data class ClusterDto(val clusterId: Int, val faceCount: Long, val coverFaceId: Long, val name: String?)
data class NameClusterRequest(val name: String)

interface HomePhotoApi {
    @GET("api/v1/months")
    suspend fun months(): List<MonthDto>

    @GET("api/v1/assets")
    suspend fun assets(
        @retrofit2.http.Query("cursor") cursor: String? = null,
        @retrofit2.http.Query("limit") limit: Int = 100,
        @retrofit2.http.Query("yearMonth") yearMonth: String? = null,
        @retrofit2.http.Query("clusterId") clusterId: Int? = null,
    ): AssetPageDto

    @GET("api/v1/faces/clusters")
    suspend fun clusters(): List<ClusterDto>

    @POST("api/v1/faces/clusters/{clusterId}/name")
    suspend fun nameCluster(
        @retrofit2.http.Path("clusterId") clusterId: Int,
        @Body request: NameClusterRequest,
    ): Map<String, Any>

    @retrofit2.http.DELETE("api/v1/assets/{id}")
    suspend fun deleteAsset(@retrofit2.http.Path("id") id: Long): Map<String, Any>

    @POST("api/v1/assets/check")
    suspend fun check(@Body request: CheckRequest): CheckResponse

    /** 201 = 신규 저장, 409 = 이미 존재(성공 취급) */
    @Multipart
    @POST("api/v1/assets")
    suspend fun upload(
        @Part file: MultipartBody.Part,
        @Part("hash") hash: RequestBody,
        @Part("fileMtime") fileMtime: RequestBody?,
    ): Response<AssetDto>
}

object ApiFactory {

    fun create(
        baseUrl: String,
        apiKey: String,
        deviceId: String? = null,
        deviceName: String? = null,
    ): HomePhotoApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES) // 대용량 동영상 업로드 대비
            .addInterceptor(
                okhttp3.logging.HttpLoggingInterceptor().apply {
                    level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                }
            )
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("X-Api-Key", apiKey)
                if (deviceId != null) {
                    builder.header("X-Device-Id", deviceId)
                    // 한글 기기명은 HTTP 헤더에 못 실리므로 URL 인코딩
                    builder.header(
                        "X-Device-Name",
                        java.net.URLEncoder.encode(deviceName ?: deviceId, "UTF-8"),
                    )
                }
                chain.proceed(builder.build())
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HomePhotoApi::class.java)
    }
}
