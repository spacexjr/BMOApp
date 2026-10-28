package com.spacexjr.saturnbmo.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Obtém a localização do aparelho sem depender do Google Play Services.
 *
 * Usa GPS e rede (Wi-Fi/celular). O resultado é enviado ao backend apenas para
 * consultar clima; nenhum histórico de localização é gravado.
 */
class BmoLocation(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission() || manager == null) return null
        return PROVIDERS
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }

    /**
     * Tenta obter uma leitura nova. Se nenhum provedor responder em [timeoutMs],
     * devolve a última localização conhecida (que pode ser nula).
     */
    @SuppressLint("MissingPermission")
    fun requestCurrent(
        timeoutMs: Long = 3000,
        onResult: (Location?) -> Unit,
    ) {
        if (!hasPermission() || manager == null) {
            onResult(null)
            return
        }
        val initial = lastKnown()
        val providers = PROVIDERS.filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
        if (providers.isEmpty()) {
            onResult(initial)
            return
        }

        val handler = Handler(Looper.getMainLooper())
        val delivered = AtomicBoolean(false)
        var listener: LocationListener? = null

        fun finish(location: Location?) {
            if (!delivered.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            listener?.let { runCatching { manager.removeUpdates(it) } }
            onResult(better(initial, location))
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = finish(location)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated na API Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        var scheduled = 0
        for (provider in providers) {
            val ok = runCatching {
                manager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
            }.isSuccess
            if (ok) scheduled++
        }
        if (scheduled == 0) {
            finish(null)
            return
        }
        handler.postDelayed({ finish(null) }, timeoutMs)
    }

    /** Versão suspensa de [requestCurrent], útil antes de enviar uma pergunta. */
    suspend fun awaitLocation(timeoutMs: Long = 2500): Location? =
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val done = AtomicBoolean(false)

            fun finish(location: Location?) {
                if (done.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(location)
                }
            }

            handler.postDelayed({ finish(lastKnown()) }, timeoutMs)
            requestCurrent(timeoutMs) { location ->
                handler.removeCallbacksAndMessages(null)
                finish(location)
            }
            continuation.invokeOnCancellation {
                handler.removeCallbacksAndMessages(null)
            }
        }

    private fun better(previous: Location?, fresh: Location?): Location? = when {
        previous == null -> fresh
        fresh == null -> previous
        fresh.time > previous.time + 30_000 -> fresh
        previous.time > fresh.time + 30_000 -> previous
        else -> if (fresh.accuracy < previous.accuracy) fresh else previous
    }

    private companion object {
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
