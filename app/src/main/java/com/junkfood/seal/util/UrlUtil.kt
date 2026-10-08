package com.junkfood.seal.util

import java.net.URI

private val urlPattern = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

/** 공유 문구에서 유효한 HTTP 링크를 추출하고 원래 순서대로 중복을 제거합니다. */
fun findURLsFromString(input: String, firstMatchOnly: Boolean = false): List<String> =
    urlPattern
        .findAll(input)
        .map { match ->
            var url = match.value.trimEnd('.', ',', ';', '!', '。', '，', '；', '！')
            // 문구의 닫는 괄호만 제거하고 URL 안의 짝이 맞는 괄호는 보존합니다.
            // ponytail: 괄호 수를 매번 셉니다. 긴 입력에서 지연이 측정되면 한 번만 집계합니다.
            while (url.isNotEmpty()) {
                val opening =
                    when (url.last()) {
                        ')' -> '('
                        ']' -> '['
                        '}' -> '{'
                        else -> break
                    }
                if (url.count { it == url.last() } <= url.count { it == opening }) break
                url = url.dropLast(1).trimEnd('.', ',', ';', '!', '。', '，', '；', '！')
            }
            url
        }
        .filter { url ->
            // 잘못된 호스트·포트·퍼센트 인코딩을 다운로드 엔진에 넘기지 않습니다.
            runCatching { URI(url).let { it.host != null && it.port in -1..65535 } }
                .getOrDefault(false)
        }
        .distinct()
        .take(if (firstMatchOnly) 1 else Int.MAX_VALUE)
        .toList()
