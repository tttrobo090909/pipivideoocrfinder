package com.pipi.videofinder

object TagFilter {

    /**
     * OCR 결과에서 영문(알파벳)과 외계어 파편을 100% 원천 차단하고,
     * 오직 2글자 이상의 순수 완성형 한글(가-힣) 단어만 선별하여 깨끗한 #태그를 생성합니다.
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

        // 길이 및 품질 우선순위 정렬 후 상위 6개 선별
        return candidateTags
            .filter { it.length in 3..14 } // '#' 포함 3자~14자
            .sortedByDescending { it.length }
            .take(6)
    }

    private fun isValidKoreanTag(word: String): Boolean {
        if (word.length < 2) return false
        // 모든 글자가 완성형 한글('가'..'힣')이어야 함 (자음/모음 낱자 제외, 영문/숫자 전면 배제)
        if (!word.all { it in '가'..'힣' }) return false
        return true
    }
}
