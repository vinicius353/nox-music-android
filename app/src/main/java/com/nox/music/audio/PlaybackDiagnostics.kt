package com.nox.music.audio

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * A privacy-conscious, player-layer diagnostic displayed when playback fails.
 * Never includes stream URLs, cookies, video titles or HTTP response bodies.
 */
object PlaybackDiagnostics {
    private fun causes(error: Throwable): List<Throwable> {
        val result = mutableListOf<Throwable>()
        var next: Throwable? = error
        while (next != null && result.size < 8 && next !in result) {
            result.add(next)
            next = next.cause
        }
        return result
    }

    private fun httpStatus(error: Throwable): Int? {
        val chain = causes(error)
        chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.let { return it.responseCode }
        // Ktor's request exceptions carry the status in their message.
        // Capture only three-digit HTTP status; never expose the rest of the text.
        val regex = Regex("""(?:HTTP[/ ]|response code\s*:?\s*|invalid:\s*)([45]\d{2})""", RegexOption.IGNORE_CASE)
        return chain.mapNotNull { regex.find(it.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }
            .firstOrNull()
    }

    private fun stage(error: Throwable): String =
        if (causes(error).any { it.message?.contains("NOX_YOUTUBE_RESOLVE_FAILED") == true })
            "resolução do áudio (InnerTune)"
        else if (httpStatus(error) != null) "download do áudio (Media3)"
        else "player (Media3)"

    fun describe(error: PlaybackException): String {
        val types = causes(error).map { it.javaClass.simpleName }
            .distinct().joinToString(" → ").take(180)
        val status = httpStatus(error)?.toString() ?: "não identificado"
        return "Código Media3: ${error.errorCodeName}\n" +
            "Etapa: ${stage(error)}\nHTTP: $status\n" +
            "Exceções: $types"
    }

    fun advice(error: PlaybackException): String {
        val status = httpStatus(error)
        val chain = causes(error)
        return when {
            status == 403 -> "O servidor recusou o áudio (HTTP 403). Pode haver restrição de acesso à faixa ou incompatibilidade da fonte de reprodução."
            status == 400 -> "A consulta ao serviço foi rejeitada (HTTP 400). A busca pode funcionar mesmo quando a requisição de reprodução falha."
            status == 401 -> "A faixa exige autenticação que esta integração não possui."
            status == 404 -> "O endereço de reprodução não foi encontrado ou expirou."
            chain.any { it.message?.contains("NOX_YOUTUBE_RESOLVE_FAILED") == true } ->
                "Não foi possível obter uma fonte de áudio do InnerTune. Este é um problema da integração, não necessariamente da internet."
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "O áudio foi recebido, mas o formato não pôde ser decodificado no dispositivo."
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Erro de conexão durante a reprodução. Teste também em outra rede."
            else -> "O player não conseguiu iniciar. Copie o diagnóstico para identificarmos a origem."
        }
    }
}
