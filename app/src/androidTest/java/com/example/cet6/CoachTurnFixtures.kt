package com.example.cet6

import android.content.Context
import com.example.cet6.data.*

/**
 * Emulator-only layout fixtures for the review screens. Texts are original and every saved turn is
 * labelled as a fixture, so it can never be mistaken for model output or exam content.
 */
object CoachTurnFixtures {
    private const val MODEL = "layout-fixture（非 AI）"

    suspend fun seed(context: Context): String {
        check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic")) {
            "Review layout fixtures are restricted to an emulator"
        }
        val repository = CoachRepository(StudyDatabase.get(context))
        val now = System.currentTimeMillis()
        val essay = CoachPractice(kind = CoachKind.WRITING.name, title = "布局样本 · 非真题 · 作文",
            material = "原创布局样本（非真题）。Directions: Write an essay that begins with the sentence “Libraries matter in the digital age.” " +
                "You should write at least 150 words but no more than 200 words.",
            answer = "Libraries matter in the digital age. Many student think that internet can replace library, but I disagree with this opinion.\n\n" +
                "First, library provide a quiet place for study. When I study in my dormitory, there are always many noise and I can not focus. " +
                "In the library, everyone keep silent, so I can concentrate on my work.\n\n" +
                "Second, libraries has many books which is not available online. Some academic books are very expensive, and students can borrow them freely.\n\n" +
                "In a word, libraries is still very important for us. We should make full use of them.",
            submittedAt = now - 60_000, updatedAt = now - 60_000)
        repository.save(essay)
        val review = listOf(
            """{"type":"score","band":8,"summary":"切题，观点清楚，但主谓一致错误较多，例证可以更具体。"}""",
            """{"type":"aspect","name":"切题","level":"好","note":"以给定句开头，回应了图书馆的价值。"}""",
            """{"type":"aspect","name":"内容","level":"一般","note":"两条理由成立，例子偏概括。"}""",
            """{"type":"aspect","name":"结构","level":"好","note":"总分总结构清楚。"}""",
            """{"type":"aspect","name":"语言","level":"待改进","note":"主谓一致和名词单复数错误反复出现。"}""",
            """{"type":"issue","quote":"Many student think","fix":"Many students think","reason":"many 后接可数名词复数。","category":"语法","level":"must"}""",
            """{"type":"issue","quote":"internet can replace library","fix":"the Internet can replace libraries","reason":"泛指图书馆用复数；Internet 前加 the。","category":"用词","level":"must"}""",
            """{"type":"issue","quote":"library provide","fix":"the library provides","reason":"主语单数，谓语用第三人称单数。","category":"语法","level":"must"}""",
            """{"type":"issue","quote":"there are always many noise","fix":"there is always a lot of noise","reason":"noise 表示噪音时不可数。","category":"语法","level":"must"}""",
            """{"type":"issue","quote":"can not focus","fix":"cannot focus","reason":"cannot 通常连写。","category":"拼写","level":"better"}""",
            """{"type":"issue","quote":"everyone keep silent","fix":"everyone keeps quiet","reason":"everyone 作主语谓语用单数；keep quiet 更常用。","category":"语法","level":"must"}""",
            """{"type":"issue","quote":"libraries has many books which is","fix":"libraries have many books that are","reason":"主语复数，从句谓语与 books 一致。","category":"语法","level":"must"}""",
            """{"type":"issue","quote":"In a word","fix":"In conclusion","reason":"结尾总结用 In conclusion 更正式。","category":"衔接","level":"better"}""",
            """{"type":"issue","quote":"libraries is still very important for us","fix":"libraries remain essential to students","reason":"主谓一致；remain essential 更具体。","category":"用词","level":"better"}""",
            """{"type":"tip","text":"第二段可以加一个具体经历，比如期末周在图书馆找到的参考书。"}""",
            """{"type":"tip","text":"每段开头用主题句点明理由，结尾呼应开头句。"}""",
            """{"type":"rewrite","text":"Libraries matter in the digital age. Many students think that the Internet can replace libraries, but I disagree.\n\nFirst, the library provides a quiet place for study. In my dormitory there is always a lot of noise and I cannot focus, while in the library everyone keeps quiet.\n\nSecond, libraries have many books that are not available online, and students can borrow expensive academic books for free.\n\nIn conclusion, libraries remain essential to students, and we should make full use of them."}"""
        ).joinToString("\n")
        repository.saveTurn(CoachTurn(practiceId = essay.id, anchor = Anchor.REVIEW, kind = TurnKind.WRITING_REVIEW,
            role = CoachTurn.ASSISTANT, content = review, model = MODEL, createdAt = now - 50_000))
        repository.saveTurn(CoachTurn(practiceId = essay.id, anchor = Anchor.REVIEW, kind = TurnKind.CHAT,
            role = CoachTurn.USER, content = "哪一处最影响得分？", createdAt = now - 40_000))
        repository.saveTurn(CoachTurn(practiceId = essay.id, anchor = Anchor.REVIEW, kind = TurnKind.CHAT,
            role = CoachTurn.ASSISTANT, content = "（布局样本）最影响得分的是**主谓一致**：\n\n- “library provide”\n- “everyone keep silent”\n\n这类错误重复出现，会让阅卷人判断语言基础不稳。",
            model = MODEL, createdAt = now - 39_000))

        val translation = CoachPractice(kind = CoachKind.TRANSLATION.name, title = "布局样本 · 非真题 · 翻译",
            material = "大学图书馆不仅提供书籍，也为学生创造安静的学习空间。随着数字资源日益丰富，图书馆的作用正在发生变化。许多图书馆开设了讨论室和电子阅览区，方便学生合作学习。",
            answer = "University libraries not only provide books, but also create quiet study space for student. With digital resources becoming more rich, " +
                "the role of library is changing. Many libraries open discussion rooms and electronic reading area.",
            submittedAt = now - 30_000, updatedAt = now - 30_000)
        repository.save(translation)
        val translationReview = listOf(
            """{"type":"score","band":11,"summary":"主要信息基本译出，第三句漏译“方便学生合作学习”，有几处单复数问题。"}""",
            """{"type":"segment","index":1,"mine":"University libraries not only provide books, but also create quiet study space for student.","verdict":"minor","note":"student 应为复数；space 可用复数 spaces。","suggestion":"University libraries not only provide books but also create quiet study spaces for students."}""",
            """{"type":"segment","index":2,"mine":"With digital resources becoming more rich, the role of library is changing.","verdict":"minor","note":"rich 的比较级是 richer；library 应为复数。","suggestion":"As digital resources become increasingly abundant, the role of libraries is changing."}""",
            """{"type":"segment","index":3,"mine":"Many libraries open discussion rooms and electronic reading area.","verdict":"error","note":"漏译“方便学生合作学习”；area 应为复数。","suggestion":"Many libraries have set up discussion rooms and electronic reading areas to facilitate collaborative learning."}""",
            """{"type":"issue","index":1,"quote":"for student","fix":"for students","reason":"泛指学生用复数。","category":"语法","level":"must"}""",
            """{"type":"issue","index":2,"quote":"more rich","fix":"richer","reason":"单音节形容词比较级用 -er。","category":"语法","level":"must"}""",
            """{"type":"issue","index":2,"quote":"the role of library","fix":"the role of libraries","reason":"泛指图书馆用复数。","category":"语法","level":"must"}""",
            """{"type":"issue","index":3,"quote":"open","fix":"have set up","reason":"“开设了”表示已经完成的设置。","category":"用词","level":"better"}""",
            """{"type":"issue","index":3,"quote":"electronic reading area.","fix":"electronic reading areas to facilitate collaborative learning.","reason":"补译“方便学生合作学习”，area 用复数。","category":"漏译","level":"must"}""",
            """{"type":"term","zh":"数字资源","en":"digital resources"}""",
            """{"type":"term","zh":"电子阅览区","en":"electronic reading areas"}""",
            """{"type":"term","zh":"合作学习","en":"collaborative learning","note":"facilitate collaborative learning 可作目的状语"}""",
            """{"type":"tip","text":"先找出每句的主干，再处理“随着……”这类状语，最后逐句核对有没有漏掉的信息点。"}"""
        ).joinToString("\n")
        repository.saveTurn(CoachTurn(practiceId = translation.id, anchor = Anchor.REVIEW, kind = TurnKind.TRANSLATION_REVIEW,
            role = CoachTurn.ASSISTANT, content = translationReview, model = MODEL, createdAt = now - 20_000))
        return "SEEDED emulator-only labelled review fixtures: writing ${essay.id}; translation ${translation.id}\n"
    }
}
