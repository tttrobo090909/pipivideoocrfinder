package com.pipi.videofinder

object TagFilter {

    /**
     * OCR 결과에서 영문(알파벳)과 외계어 파편을 100% 원천 차단하고,
     * 오직 2글자 이상의 순수 완성형 한글(가-힣) 단어만 선별하여 깨끗한 #태그를 생성합니다.
     * 유사 중복 태그(예: 하나님면랑하라, 하나님단랑하라 등)를 필터링하여 깔끔하게 정리합니다.
     */
    fun extractCleanTags(rawTexts: List<String>): List<String> {
        val candidateTags = mutableSetOf<String>()

        for (raw in rawTexts) {
            // 1. 영문(a-zA-Z) 및 특수문자 전면 제거, 오직 순수 한글과 공백만 보존
            val koreanOnly = raw.replace("\n", " ")
                .replace(Regex("[^가-힣\\s]"), " ")
                .trim()

            val words = koreanOnly.split(Regex("\\s+")).filter { it.isNotBlank() }

            // 2. 순수 한글 단어 선별 (2글자 이상)
            for (word in words) {
                if (isValidKoreanTag(word)) {
                    candidateTags.add("#$word")
                }
            }

            // 3. 자막 구문 결합 (예: "온땅이여", "반전완료", "영으로 진리로" -> "영으로진리로")
            if (words.size in 2..3) {
                val combined = words.joinToString("")
                if (combined.length in 4..12 && isValidKoreanTag(combined)) {
                    candidateTags.add("#$combined")
                }
            }
        }

        // 길이 순 정렬 후 유사 중복 태그 제거
        val sortedList = candidateTags
            .filter { it.length in 3..14 } // '#' 포함 3자~14자
            .sortedByDescending { it.length }

        val deduplicated = mutableListOf<String>()
        for (tag in sortedList) {
            val raw = tag.removePrefix("#")
            // 이미 추가된 더 길거나 대표적인 태그와 너무 많이 겹치는지 검사
            val isDuplicate = deduplicated.any { existing ->
                val exRaw = existing.removePrefix("#")
                exRaw.contains(raw) || isSimilar(raw, exRaw)
            }
            if (!isDuplicate) {
                deduplicated.add(tag)
            }
            if (deduplicated.size >= 6) break
        }

        return deduplicated
    }

    private fun isValidKoreanTag(word: String): Boolean {
        if (word.length < 2) return false
        // 모든 글자가 완성형 한글('가'..'힣')이어야 함 (자음/모음 낱자 제외, 영문/숫자 전면 배제)
        if (!word.all { it in '가'..'힣' }) return false
        return true
    }

    /**
     * 두 단어가 1글자 차이 이하의 유사 단어(오타 변형)인지 판별
     */
    private fun isSimilar(a: String, b: String): Boolean {
        if (Math.abs(a.length - b.length) > 1) return false
        var diff = 0
        val minLen = Math.min(a.length, b.length)
        for (i in 0 until minLen) {
            if (a[i] != b[i]) {
                diff++
                if (diff > 1) return false
            }
        }
        return true
    }
}
