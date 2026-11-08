#!/usr/bin/env python3
"""
GPL-3.0 InnerTune compatibility edits for November 2026.
These edits keep the original upstream module and publicly document each difference.
They are inspired by contemporary InnerTube request metadata in
MetrolistGroup/innertubex, licensed GPL-3.0.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])
models = root / "com/zionhuang/innertube/models/YouTubeClient.kt"
api = root / "com/zionhuang/innertube/InnerTube.kt"


def substitute(file, before, after):
    data = file.read_text()
    occurrences = data.count(before)
    if occurrences != 1:
        raise RuntimeError(f"Expected exactly one match in {file.name}: {before[:70]} (got {occurrences})")
    file.write_text(data.replace(before, after))


# The old InnerTune WEB_REMIX client version is from 2022.
substitute(models, 'clientVersion = "1.20220606.03.00"', 'clientVersion = "1.20260707.12.00"')
substitute(models,
    'private const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.157 Safari/537.36"',
    'private const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"')
substitute(models,
    'clientVersion = "19.29.1"',
    'clientVersion = "21.26.4"')
substitute(models,
    'private const val USER_AGENT_IOS = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"',
    'private const val USER_AGENT_IOS = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"')

# YouTube's X-YouTube-Client-Name header uses a numeric client ID, not the
# human-readable clientName in older InnerTune. Keep other profile IDs unchanged.
substitute(api,
    'append("X-YouTube-Client-Name", client.clientName)',
    '''append("X-YouTube-Client-Name", when (client.clientName) {
                "WEB_REMIX" -> "67"
                "IOS" -> "5"
                "ANDROID_MUSIC" -> "21"
                "WEB" -> "1"
                "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> "85"
                else -> client.clientName
            })''')
# The current public YouTube search endpoint accepts clients without a key
# query argument. This older key may otherwise produce HTTP 400.
substitute(api, 'parameter("key", client.api_key)', '// No public API key parameter needed for modern InnerTube search/player.')
substitute(api,
    'append("x-origin", "https://music.youtube.com")',
    'append("x-origin", "https://music.youtube.com")\n            append("Origin", "https://music.youtube.com")')

# Updated InnerTube requests include request/user context blocks. InnerTune 2025
# predates these required fields; without them even unfiltered search may be 400.
context = root / "com/zionhuang/innertube/models/Context.kt"
substitute(context,
    '    val thirdParty: ThirdParty? = null,',
    '''    val thirdParty: ThirdParty? = null,
    val request: Request = Request(),
    val user: User = User(),''')
substitute(context,
    '''    @Serializable
    data class ThirdParty(''',
    '''    @Serializable
    data class Request(
        val internalExperimentFlags: List<String> = emptyList(),
        val useSsl: Boolean = true,
    )

    @Serializable
    data class User(
        val lockedSafetyMode: Boolean = false,
        val onBehalfOfUser: String? = null,
    )

    @Serializable
    data class ThirdParty(''')

print("Applied NOX InnerTune modern-client compatibility edits (GPL-3.0)")
