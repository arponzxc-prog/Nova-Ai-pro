package com.example.tools

import com.example.model.InputMode

/**
 * লোকাল আইডেন্টিটি রেসপন্ডার (অফলাইন-প্রথম, শূন্য API কল)।
 * অ্যাসিস্ট্যান্টের নাম ("NOVA AI") ও নির্মাতা ("ARPON") সংক্রান্ত প্রশ্নে
 * সরাসরি নির্ভুল উত্তর দেয় এবং সাধারণ "নাম / তৈরি" বিষয়ক ফলস পজিটিভ এড়িয়ে চলে।
 */
object IdentityResponder {

    enum class IdentityKind {
        NAME,
        CREATOR,
        BOTH
    }

    data class IdentityReply(
        val replyText: String,
        val isEnglish: Boolean,
        val kind: IdentityKind
    )

    private val FALSE_POSITIVE_NAME_TARGETS = listOf(
        "আমার নাম", "amr nam", "amar nam", "my name", "who am i",
        "রহিমের নাম", "করিমের নাম", "তার নাম", "ওর নাম", "বন্ধুর নাম", "বাবার নাম", "মায়ের নাম", "মায়ের নাম",
        "file name", "filename", "model name", "package name", "class name", "app name",
        "database name", "table name", "variable name", "domain name", "user name", "username",
        "ফাইলের নাম", "মডেলের নাম", "প্যাকেজের নাম", "অ্যাপের নাম", "ডাটাবেজের নাম",
        "তোমার নাম পরিবর্তন", "আপনার নাম পরিবর্তন", "নাম পরিবর্তন", "নাম বদলা",
        "change your name", "rename yourself", "rename you", "call you "
    )

    private val FALSE_POSITIVE_CREATION_COMMANDS = listOf(
        "তৈরি করে দাও", "বানিয়ে দাও", "বানায়ে দাও", "বানাও", "লিখে দাও", "করে দাও",
        "অ্যাপ তৈরি", "কোড তৈরি", "প্রজেক্ট তৈরি", "ওয়েবসাইট তৈরি", "ওয়েবসাইট তৈরি", "ছবি তৈরি", "গান তৈরি",
        "create a ", "create an ", "make a ", "make an ", "build a ", "build an ",
        "write a ", "generate a ", "how to make", "how to create", "how to build",
        "who created java", "who created python", "who created android", "who invented",
        "কে তৈরি করেছে এই পৃথিবী", "কে সৃষ্টি করেছে"
    )

    // বাংলা ও ইংরেজি/বাংলিশ নির্মাতা প্রশ্ন
    private val CREATOR_PATTERNS = listOf(
        Regex("(তোমাকে|আপনাকে|তোর|তুই|নেক্সা|nexa|নক্সা|নোভা|nova|অ্যাসিস্ট্যান্ট|assistant|এই\\s*অ্যাপ|অ্যাপটি|অ্যাপটা)\\s*.*?(কে|কোন)\\s*(বানিয়েছ|বানায়েছ|বানাইছ|তৈরি\\s*করেছ|তৈরি\\s*করছ|ডেভেলপ\\s*করেছ|বানিয়েছিল|লিখেছ)"),
        Regex("কে\\s*(বানিয়েছে|বানায়েছে|বানাইছে|তৈরি\\s*করেছে|তৈরি\\s*করছে|ডেভেলপ\\s*করেছে)\\s*(তোমাকে|আপনাকে|তোর|নেক্সা|nexa|নোভা|nova|এই\\s*অ্যাপ)?"),
        Regex("(তোমার|আপনার|তোর)\\s*(ডেভেলপার|নির্মাতা|স্রষ্টা|প্রস্তুতকারক|ক্রিয়েটর|মালিক|ডিজাইনার)\\s*(কে|নাম\\s*কী|নাম\\s*কি)?"),
        Regex("(who\\s*(made|created|built|developed|programmed|designed)\\s*(you|nexa|nova|this\\s*app|this\\s*assistant))"),
        Regex("(who\\s*(?:is|'s)\\s*(your|the)\\s*(creator|developer|maker|author|builder|owner))"),
        Regex("(your\\s*(developer|creator|maker|author)(\\s*name)?)"),
        Regex("((tomake|apnake|toke|nexa\\s*ke|nova\\s*ke)\\s*ke\\s*(baniyeche|baniyese|baniyechey|baniache|toiri\\s*koreche|toiri\\s*korse|develop\\s*koreche))"),
        Regex("(ke\\s*(baniyeche|toiri\\s*koreche|baniyese)\\s*(tomake|apnake|nexa|nova))"),
        Regex("((tomar|apnar)\\s*(developer|creator|malik|nirmata)\\s*(ke|nam\\s*ki))")
    )

    // বাংলা ও ইংরেজি/বাংলিশ নাম/পরিচয় প্রশ্ন
    private val NAME_PATTERNS = listOf(
        Regex("^(হ্যালো\\s+|হাই\\s+|আচ্ছা\\s+|বলো\\s+|বলোতো\\s+|বলুন\\s+)?(তোমার|আপনার|তোর)\\s*(নাম|শুভ\\s*নাম)\\s*(কি|কী|টা\\s*কি|টা\\s*কী)?\\s*[?!।.]*$"),
        Regex("^(তুমি|আপনি|তুই)\\s*(আসলে\\s*)?কে\\s*[?!।.]*$"),
        Regex("(তোমাকে|আপনাকে)\\s*(কী|কি)\\s*নামে\\s*ডাকব"),
        Regex("(তোমাকে|আপনাকে)\\s*(কী|কি)\\s*বলে\\s*ডাকব"),
        Regex("(তোমার|আপনার)\\s*(নিজের\\s*)?পরিচয়\\s*(দাও|দিন|কী|কি)?"),
        Regex("^what('?s|\\s+is)\\s+your\\s+name\\s*[?!.]*$"),
        Regex("^(hello\\s+|hi\\s+|hey\\s+)?who\\s+are\\s+you\\s*[?!.]*$"),
        Regex("^tell\\s+me\\s+your\\s+name\\s*[?!.]*$"),
        Regex("^may\\s+i\\s+know\\s+your\\s+name\\s*[?!.]*$"),
        Regex("^introduce\\s+yourself\\s*[?!.]*$"),
        Regex("^(tomar|apnar|tor)\\s+nam\\s+(ki|kee|k)\\s*[?!.]*$"),
        Regex("^(tumi|apni|tui)\\s+ke\\s*[?!.]*$")
    )

    private val ENGLISH_HINT_REGEX = Regex("[a-zA-Z]")
    private val BENGALI_CHAR_REGEX = Regex("[\\u0980-\\u09FF]")

    fun match(query: String, inputMode: InputMode = InputMode.TEXT): IdentityReply? {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return null
        val lower = normalized.lowercase()

        // দীর্ঘ বহু-বাক্যের অনুরোধ হলে সাধারণ LLM প্রম্পট হিসেবে যেতে দাও
        if (normalized.length > 90 && normalized.count { it == ' ' } > 14) {
            return null
        }

        // ফলস পজিটিভ বাদ দেওয়া
        if (FALSE_POSITIVE_NAME_TARGETS.any { lower.contains(it) }) {
            // তবে একই বাক্যে আলাদাভাবে "কে বানিয়েছে" থাকলে শুধু ক্রিয়েটর ম্যাচ হতে পারে
            if (FALSE_POSITIVE_CREATION_COMMANDS.any { lower.contains(it) }) return null
            val creatorMatchOnly = CREATOR_PATTERNS.any { it.containsMatchIn(lower) }
            if (!creatorMatchOnly) return null
        }

        if (FALSE_POSITIVE_CREATION_COMMANDS.any { lower.contains(it) }) {
            return null
        }

        val matchesCreator = CREATOR_PATTERNS.any { it.containsMatchIn(lower) }
        val matchesName = NAME_PATTERNS.any { it.containsMatchIn(lower) }

        if (!matchesCreator && !matchesName) return null

        val hasBengali = BENGALI_CHAR_REGEX.containsMatchIn(normalized)
        val isEnglish = !hasBengali && ENGLISH_HINT_REGEX.containsMatchIn(normalized) &&
            !isBanglish(lower)

        val kind = when {
            matchesCreator && matchesName -> IdentityKind.BOTH
            matchesCreator -> IdentityKind.CREATOR
            else -> IdentityKind.NAME
        }

        val replyText = when (kind) {
            IdentityKind.NAME -> when {
                isEnglish && inputMode == InputMode.VOICE -> "My name is NOVA AI. How can I help you?"
                isEnglish -> "My name is NOVA AI."
                inputMode == InputMode.VOICE -> "আমার নাম NOVA AI। বলুন, কীভাবে সাহায্য করতে পারি?"
                else -> "আমার নাম NOVA AI।"
            }
            IdentityKind.CREATOR -> when {
                isEnglish && inputMode == InputMode.VOICE -> "I was created by ARPON."
                isEnglish -> "I was created by ARPON."
                inputMode == InputMode.VOICE -> "আমাকে তৈরি করেছেন ARPON।"
                else -> "আমাকে তৈরি করেছেন ARPON।"
            }
            IdentityKind.BOTH -> when {
                isEnglish -> "My name is NOVA AI, and I was created by ARPON."
                else -> "আমার নাম NOVA AI, এবং আমাকে তৈরি করেছেন ARPON।"
            }
        }

        return IdentityReply(
            replyText = replyText,
            isEnglish = isEnglish,
            kind = kind
        )
    }

    private fun isBanglish(lower: String): Boolean {
        return listOf("tomar", "apnar", "tomake", "apnake", "baniyeche", "baniyese", "nam ki", "tumi ke", "apni ke", "toiri koreche")
            .any { lower.contains(it) }
    }
}
