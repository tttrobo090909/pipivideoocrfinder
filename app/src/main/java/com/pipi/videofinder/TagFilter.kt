package com.pipi.videofinder

object TagFilter {

    /**
     * OCR로 검출된 문장/단어 목록에서 오타와 노이즈(외계어 파편, 특수문자, 1글자 잡음)를 걸러내고
     * 의미 있는 정제된 #태그 목록을 생성합니다.
     */
    fun extractCleanTags(rawTexts: List<String>): List<String> {
        val candidateTags = mutableSetOf<String>()

        for (raw in rawTexts) {
            // 줄바꿈을 공백으로 치환하고 특수문자 정제
            val cleaned = raw.replace("\n", " ")
                .replace(Regex("[^가-힣a-zA-Z0-9\\s]"), " ")
                .trim()

            // 띄어쓰기 단위로 단어 분리
            val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }

            // 1. 단어 단위 추출 (2글자 이상, 순수 자음/모음만 있는 경우 제외)
            for (word in words) {
                if (isValidTagWord(word)) {
                    candidateTags.add("#$word")
                }
            }

            // 2. 2~3단어로 구성된 자연스러운 구문(예: "베드로의 고백처럼" -> "베드로의고백처럼")도 태그 후보로 병합
            if (words.size in 2..4) {
                val combined = words.joinToString("")
                if (combined.length in 4..16 && isValidTagWord(combined)) {
                    candidateTags.add("#$combined")
                }
            }
        }

        // 길이 및 의미 우선순위 정렬 (너무 짧거나 긴 것 제외, 상위 태그 선별)
        return candidateTags
            .filter { it.length in 3..18 } // '#' 포함 3자~18자
            .sortedByDescending { it.length }
            .take(6) // 영상당 가장 유의미한 상위 6개 태그 선별
    }

    private fun isValidTagWord(word: String): Boolean {
        if (word.length < 2) return false
        // 자음/모음만 있는 경우(ㅋㅋㅋ, ㅎㅎ, ㅠㅠ) 제외
        if (word.all { it in 'ㄱ'..'ㅣ' }) return false
        // 숫자만 있는 경우(1234) 제외
        if (word.all { it.isDigit() }) return false
        return true
    }
}
