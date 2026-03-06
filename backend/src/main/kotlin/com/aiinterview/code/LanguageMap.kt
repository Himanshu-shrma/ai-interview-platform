package com.aiinterview.code

object LanguageMap {

    val map: Map<String, Int> = mapOf(
        // Web
        "javascript"    to 63,
        "typescript"    to 74,
        // Systems
        "c"             to 50,
        "cpp"           to 54,
        "c++"           to 54,
        "rust"          to 73,
        "go"            to 60,
        // JVM
        "java"          to 62,
        "kotlin"        to 78,
        "scala"         to 81,
        // Scripting
        "python"        to 71,
        "python3"       to 71,
        "ruby"          to 72,
        "php"           to 68,
        "perl"          to 85,
        "swift"         to 83,
        // Data
        "r"             to 80,
        // Shell
        "bash"          to 46,
        // Other
        "csharp"        to 51,
        "c#"            to 51,
        "fsharp"        to 87,
        "f#"            to 87,
        "lua"           to 64,
        "haskell"       to 61,
        "elixir"        to 57,
        "clojure"       to 86,
    )

    fun getLanguageId(language: String): Int =
        map[language.lowercase().trim()]
            ?: throw UnsupportedLanguageException(language)

    fun getSupportedLanguages(): List<String> = map.keys.sorted()

    fun isSupported(language: String): Boolean =
        map.containsKey(language.lowercase().trim())
}

class UnsupportedLanguageException(language: String) :
    IllegalArgumentException(
        "Unsupported language: '$language'. " +
        "Supported: ${LanguageMap.getSupportedLanguages().joinToString()}",
    )
