package com.example.sonara.domain.lyrics

import org.junit.Test

class RealWorldCorpusAuditTest {

    data class LyricSample(
        val category: String,
        val native: String,
        val internetStyle: String,
        val notes: String = ""
    )

    @Test
    fun auditCorpus() {
        val corpus = listOf(
            // 1. Modern Bollywood & Contemporary
            LyricSample("Modern Bollywood", "धूप टूट के काँच की तरह चुभ गई तो क्या", "dhoop toot ke kaanch ki tarah chubh gayi to kya", "Karvaan (Dhurandhar)"),
            LyricSample("Modern Bollywood", "अब देखा जाएगा इश्क़ जलाकर आ गया", "ab dekha jaayega ishq jalaakar aa gaya", "Karvaan (Dhurandhar)"),
            LyricSample("Modern Bollywood", "अपना बना ले पिया अपना बना ले मुझे", "apna bana le piya apna bana le mujhe", "Bhediya"),
            LyricSample("Modern Bollywood", "केसरिया तेरा इश्क़ है पिया रंग जाऊँ जो मैं हाथ लगाऊँ", "kesariya tera ishq hai piya rang jaaun jo main haath lagaun", "Brahmastra"),
            LyricSample("Modern Bollywood", "तेरे हवाले मेरा सब कुछ है", "tere hawaale mera sab kuch hai", "Laal Singh Chaddha"),
            LyricSample("Modern Bollywood", "रातां लम्बियाँ लम्बियाँ रे कटे तेरे संगियाँ संगियाँ रे", "raatan lambiyan lambiyan re kate tere sangiyan sangiyan re", "Shershaah"),
            LyricSample("Modern Bollywood", "माना कि हम यार नहीं लो तय है कि प्यार नहीं", "maana ki hum yaar nahi lo tay hai ki pyaar nahi", "Meri Pyaari Bindu"),
            LyricSample("Modern Bollywood", "हवा के झोंके आज मौसमों से रूठ गए", "hawa ke jhonke aaj mausamon se rooth gaye", "Lootera"),
            LyricSample("Modern Bollywood", "दिल दियां गल्लां करांगे रोज़ बैठ के", "dil diyan gallan karange roz baith ke", "Tiger Zinda Hai"),

            // 2. Romantic & Melodic Classics
            LyricSample("Romantic Classic", "तुम ही हो अब तुम ही हो ज़िन्दगी अब तुम ही हो", "tum hi ho ab tum hi ho zindagi ab tum hi ho", "Aashiqui 2"),
            LyricSample("Romantic Classic", "चैन भी मेरा दर्द भी मेरी आशिक़ी अब तुम ही हो", "chain bhi mera dard bhi meri aashiqui ab tum hi ho", "Aashiqui 2"),
            LyricSample("Romantic Classic", "पहला नशा पहला खुमार नया प्यार है नया इंतज़ार", "pehla nasha pehla khumaar naya pyaar hai naya intezaar", "JJWS"),
            LyricSample("Romantic Classic", "कर लूँ मैं क्या अपना हाल ऐ दिल-ए-बेक़रार", "kar loon main kya apna haal ae dil-e-beqaraar", "JJWS"),
            LyricSample("Romantic Classic", "तुझे देखा तो ये जाना सनम प्यार होता है दीवाना सनम", "tujhe dekha to ye jaana sanam pyaar hota hai deewana sanam", "DDLJ"),
            LyricSample("Romantic Classic", "अब यहाँ से कहाँ जाएँ हम तेरी बाहों में मर जाएँ हम", "ab yahaan se kahan jaayen hum teri baahon mein mar jaayen hum", "DDLJ"),
            LyricSample("Romantic Classic", "साँसों की ज़रूरत है जैसे ज़िन्दगी के लिए", "saanson ki zaroorat hai jaise zindagi ke liye", "Aashiqui"),
            LyricSample("Romantic Classic", "बस एक सनम चाहिए आशिक़ी के लिए", "bas ek sanam chahiye aashiqui ke liye", "Aashiqui"),

            // 3. Sufi & Poetic / Ghazal
            LyricSample("Sufi & Poetic", "कुन फ़ाया कुन कुन फ़ाया कुन जब कहीं पे कुछ नहीं भी नहीं था", "kun faya kun kun faya kun jab kahin pe kuch nahi bhi nahi tha", "Rockstar"),
            LyricSample("Sufi & Poetic", "तेरे ज़िक्र का ज़ुबां पे स्वाद रखना", "tere zikr ka zubaan pe swaad rakhna", "Ae Dil Hai Mushkil"),
            LyricSample("Sufi & Poetic", "अच्छा चलता हूँ दुआओं में याद रखना", "achcha chalta hoon duaaon mein yaad rakhna", "Ae Dil Hai Mushkil"),
            LyricSample("Sufi & Poetic", "मेरे रश्के क़मर तूने पहली नज़र जब नज़र से मिलाई मज़ा आ गया", "mere rashke qamar tune pehli nazar jab nazar se milayi maza aa gaya", "Baadshaho"),
            LyricSample("Sufi & Poetic", "हंगामा है क्यों बरपा थोड़ी सी जो पी ली है", "hungama hai kyon barpa thodi si jo pee li hai", "Ghulam Ali"),
            LyricSample("Sufi & Poetic", "डाका तो नहीं डाला चोरी तो नहीं की है", "daaka to nahi daala chori to nahi ki hai", "Ghulam Ali"),
            LyricSample("Sufi & Poetic", "होशवालों को ख़बर क्या बेख़ुदी क्या चीज़ है", "hoshwaalon ko khabar kya bekhudi kya cheez hai", "Sarfarosh"),
            LyricSample("Sufi & Poetic", "इश्क़ कीजिए फिर समझिए ज़िन्दगी क्या चीज़ है", "ishq kijiye phir samajhiye zindagi kya cheez hai", "Sarfarosh"),

            // 4. Sanskrit / Tatsama Vocabulary
            LyricSample("Sanskrit Tatsama", "सत्यमेव जयते नानृतं", "satyameva jayate naanritam", "Mundaka Upanishad"),
            LyricSample("Sanskrit Tatsama", "विद्या ददाति विनयं विनयाद् याति पात्रताम्", "vidya dadaati vinayam vinayaad yaati paatrataam", "Hitopadesha"),
            LyricSample("Sanskrit Tatsama", "आत्मविश्वास ही सफलता का रहस्य है", "aatmavishwaas hi safalta ka rahasya hai", "Philosophical"),
            LyricSample("Sanskrit Tatsama", "ज्ञान और विज्ञान का संगम", "gyan aur vigyan ka sangam", "Modern Tatsama"),
            LyricSample("Sanskrit Tatsama", "यज्ञ और तपस्या से मोक्ष की प्राप्ति", "yagya aur tapasya se moksh ki praapti", "Traditional"),
            LyricSample("Sanskrit Tatsama", "सर्व धर्म समभाव और विश्व शांति", "sarva dharma sambhav aur vishwa shaanti", "Tatsama"),

            // 5. Urdu / Persian Vocabulary with Nukta
            LyricSample("Urdu/Persian Nukta", "ख़्वाबों के परिंदे उड़ते हैं खुले आसमान में", "khwaabon ke parinde udte hain khule aasmaan mein", "ZNMD"),
            LyricSample("Urdu/Persian Nukta", "ग़म का फ़साना बन गया अफ़साना", "gham ka fasaana ban gaya afsaana", "Urdu Poetry"),
            LyricSample("Urdu/Persian Nukta", "क़िस्मत की लकीरों में लिखा था क्या", "qismat ki lakeeron mein likha tha kya", "Poetic"),
            LyricSample("Urdu/Persian Nukta", "ज़िन्दगी गुलज़ार है हर मोड़ पे बहार है", "zindagi gulzaar hai har mod pe bahaar hai", "Urdu Phrase"),
            LyricSample("Urdu/Persian Nukta", "फ़लसफ़ा मोहब्बत का कोई समझा नहीं", "falsafa mohabbat ka koi samjha nahi", "Urdu Poetry"),
            LyricSample("Urdu/Persian Nukta", "ख़ुदा हाफ़िज़ अलविदा मेरे दोस्त", "khuda haafiz alvida mere dost", "Farewell"),

            // 6. Conversational, Pronouns & Auxiliaries
            LyricSample("Conversational", "वो कहाँ जा रहे हैं और हम कहाँ आ गए", "woh kahan ja rahe hain aur hum kahan aa gaye", "Dialogue"),
            LyricSample("Conversational", "ये बात सही नहीं है", "yeh baat sahi nahi hai", "Dialogue"),
            LyricSample("Conversational", "क्या हुआ तेरा वादा वो क़सम वो इरादा", "kya hua tera waada woh qasam woh iraada", "Classic"),
            LyricSample("Conversational", "भूलेगा दिल जिस दिन तुम्हें वो दिन ज़िन्दगी का आख़िरी दिन होगा", "bhoolega dil jis din tumhein woh din zindagi ka aakhiri din hoga", "Classic"),
            LyricSample("Conversational", "मेरी माँ ने मुझे सब कुछ सिखाया", "meri maa ne mujhe sab kuch sikhaya", "Colloquial"),
            LyricSample("Conversational", "उसका मान रखना हमारा फ़र्ज़ है", "uska maan rakhna humara farz hai", "Colloquial"),

            // 7. Rap, Hip-Hop & Fast Cadence
            LyricSample("Rap/Hip-Hop", "अपना टाइम आएगा तू नंगा ही तो आया है क्या घंटा लेकर जाएगा", "apna time aayega tu nanga hi to aaya hai kya ghanta lekar jaayega", "Gully Boy"),
            LyricSample("Rap/Hip-Hop", "गली गली में शोर है सब चुपचाप देख रहे हैं", "gali gali mein shor hai sab chupchaap dekh rahe hain", "Gully Boy"),
            LyricSample("Rap/Hip-Hop", "काम ऐसा करो कि नाम हो जाए", "kaam aisa karo ki naam ho jaaye", "Hip Hop"),
            LyricSample("Rap/Hip-Hop", "रास्ते मुश्किल हैं पर हम रुकने वाले नहीं", "raaste mushkil hain par hum rukne wale nahi", "Hip Hop"),

            // 8. Grammatical Verbs (Glides, Subjunctive, Imperative, Past)
            LyricSample("Verb Morphology", "आओ बैठो बातें करें और हँसें", "aao baitho baatein karein aur hansein", "Imperative/Subjunctive"),
            LyricSample("Verb Morphology", "सबको जाने दो और उसे आने दो", "sabko jaane do aur use aane do", "Infinitive+Imperative"),
            LyricSample("Verb Morphology", "उसने चाय पी और पानी पिया", "usne chai pee aur paani piya", "Past tense"),
            LyricSample("Verb Morphology", "हम चले गए और वो रह गए", "hum chale gaye aur woh reh gaye", "Compound verb"),
            LyricSample("Verb Morphology", "तुमने क्या किया और उसने क्या दिया", "tumne kya kiya aur usne kya diya", "Past tense"),
            LyricSample("Verb Morphology", "जीते रहो और ख़ुश रहो", "jeete raho aur khush raho", "Continuous blessing"),
            LyricSample("Verb Morphology", "पानी पियोगे या चाय लोगे", "paani piyoge ya chai loge", "Future colloquial")
        )

        println("==========================================================================================")
        println("REAL-WORLD HINDI ROMANIZATION FORENSIC CORPUS AUDIT (${corpus.size} PHRASES)")
        println("==========================================================================================")

        for ((idx, sample) in corpus.withIndex()) {
            val sonaraOutput = RomanizationEngine.transliterate(sample.native)
            println("[#${idx + 1}] [${sample.category}]")
            println("  NATIVE   : ${sample.native}")
            println("  INTERNET : ${sample.internetStyle}")
            println("  SONARA   : $sonaraOutput")
            if (sample.notes.isNotBlank()) println("  CONTEXT  : ${sample.notes}")
            println("------------------------------------------------------------------------------------------")
        }
    }
}
