package com.example.cet6

import com.example.cet6.data.*
import org.junit.Assert.*
import org.junit.Test

class CoachProtocolTest {
    @Test fun jsonLinesToleratesFencesPrettyPrintingAndAnUnfinishedTail() {
        val text = "```json\n{\"type\":\"score\",\"band\":11,\"summary\":\"ok {braces} \\\"quoted\\\"\"}\n" +
            "{\n  \"type\": \"tip\",\n  \"text\": \"two\"\n}\n```\n{\"type\":\"issue\",\"quote\":\"unfinished"
        val objects = JsonLines.objects(text)
        assertEquals(2, objects.size)
        assertEquals("ok {braces} \"quoted\"", objects[0].getString("summary"))
        assertEquals("two", objects[1].getString("text"))
    }

    @Test fun reviewParsesEveryLineTypeAndMapsRawScoresToBands() {
        val review = CoachReview.parse("""
            {"type":"score","band":10,"summary":"切题，少量错误"}
            {"type":"aspect","name":"语言","level":"一般","note":"时态不稳"}
            {"type":"issue","quote":"Libraries helps","fix":"Libraries help","reason":"主谓一致","category":"语法","level":"must"}
            {"type":"issue","quote":"very good","fix":"valuable","reason":"更具体","category":"用词","level":"better"}
            {"type":"segment","index":2,"mine":"It grows.","verdict":"minor","note":"时态","suggestion":"It has grown."}
            {"type":"term","zh":"丝绸之路","en":"the Silk Road"}
            {"type":"tip","text":"多用具体例子"}
            {"type":"rewrite","text":"Libraries help students."}
            {"type":"unknown","text":"ignored"}
        """.trimIndent())
        assertTrue(review.structured)
        assertEquals(11, review.score!!.band)
        assertEquals(listOf(true, false), review.issues.map { it.must })
        assertEquals(listOf(0, 1), review.issues.map { it.id })
        assertEquals(2, review.segments.single().index)
        assertEquals("the Silk Road", review.terms.single().en)
        assertEquals(listOf("多用具体例子"), review.tips)
        assertEquals("Libraries help students.", review.rewrite)
        assertEquals(14, CoachReview.band(13)); assertEquals(0, CoachReview.band(0)); assertNull(CoachReview.band(99))
        assertFalse(CoachReview.parse("## 普通 Markdown 批改\n- 第一处").structured)
    }

    @Test fun decisionsRoundTripAndNeverHoldBothStates() {
        val d = ReviewDecisions().accept(1).dismiss(2).accept(2)
        assertEquals(setOf(1, 2), d.accepted)
        assertTrue(d.dismissed.isEmpty())
        assertEquals(d, ReviewDecisions.decode(d.encode()))
        assertEquals(ReviewDecisions(), ReviewDecisions.decode("not json"))
        assertEquals(setOf(1), d.reset(2).accepted)
    }

    @Test fun marksMatchExactTextFirstThenLooseWhitespaceQuotesAndCase() {
        val text = "It’s   clear that\nlibraries helps. Libraries helps too."
        assertEquals(listOf(18..32), TextMarks.occurrences(text, "libraries helps"))
        assertEquals(listOf(35..49), TextMarks.occurrences(text, "Libraries helps"))
        assertEquals(0..16, TextMarks.locate(text, "it's clear that"))
        assertEquals(listOf(18..32, 35..49), TextMarks.occurrences(text, "LIBRARIES  HELPS"))
        assertEquals(listOf(0..6, 12..18, null), TextMarks.place("the cat and the cat", listOf("the cat", "the cat", "dog")))
        val fixed = TextMarks.apply("ab cd ef", listOf(0..1 to "AB", 3..4 to "CD", 3..7 to "skip"))
        assertEquals("AB CD ef", fixed)
    }

    @Test fun wordDiffKeepsUnchangedTextAndMarksEdits() {
        val pieces = WordDiff.diff("Libraries helps students.", "Libraries help students a lot.")
        assertEquals("Libraries help students a lot.", pieces.filter { it.kind != DiffKind.REMOVED }.joinToString("") { it.text })
        assertEquals("Libraries helps students.", pieces.filter { it.kind != DiffKind.ADDED }.joinToString("") { it.text })
        assertTrue(WordDiff.changed(pieces) >= 2)
    }

    @Test fun passageLayoutReflowsPrintedLinesButKeepsParagraphs() {
        val printed = "It is clear that people know \nwhat to do.  \nThere are three factors. First, they \nmust see it.  \n\nBeyond that, everyone \nshould act."
        val paragraphs = PassageLayout.paragraphs(printed)
        assertEquals(listOf("It is clear that people know what to do.", "There are three factors. First, they must see it.",
            "Beyond that, everyone should act."), paragraphs)
        assertEquals(2, PassageLayout.paragraphOf(printed, "First, they must see it."))
        assertEquals(listOf("First.", "Second paragraph."), PassageLayout.paragraphs("First.\r\n\r\nSecond paragraph."))
        assertNull(PassageLayout.paragraphOf(printed, "Invented sentence that is absent."))
    }

    @Test fun chineseSourceIsJoinedAcrossPrintedBreaksAndSplitIntoSentences() {
        val source = "竹子在中国分布广泛，品种丰富。竹子实用性强，用\n于生产。 他说：“好。”洋山港(Yangshan\nPort)很大！最后一句没有句号"
        assertEquals("竹子在中国分布广泛，品种丰富。竹子实用性强，用于生产。他说：“好。”洋山港(Yangshan Port)很大！最后一句没有句号",
            TranslationCoach.normalized(source))
        assertEquals(listOf("竹子在中国分布广泛，品种丰富。", "竹子实用性强，用于生产。", "他说：“好。”", "洋山港(Yangshan Port)很大！", "最后一句没有句号"),
            TranslationCoach.sentences(source))
    }

    @Test fun writingTaskReadsOpeningSentenceAndLimitsOnlyFromTheTaskText() {
        val quoted = WritingTask.of("Directions: write an essay that begins with the sentence “There is a growing awareness of digital skills. ” " +
            "You should write at least 150 words but no more than 200 words.")
        assertEquals("There is a growing awareness of digital skills.", quoted.opening)
        assertEquals(150, quoted.minWords); assertEquals(200, quoted.maxWords)
        val above = WritingTask.of("People are now increasingly aware of the danger of appearance anxiety.\n\nDirections: write an essay that " +
            "begins with the sentence above. You should write at least 150 words but no more than 200 words (not including the sentence given).")
        assertEquals("People are now increasingly aware of the danger of appearance anxiety.", above.opening)
        assertTrue(above.excludesOpening)
        assertEquals(WritingTask(null, null, null, false), WritingTask.of("自拟题目：谈谈图书馆"))
        assertEquals("Young people’s choices matter.", WritingTask.of("begins with the sentence “Young people’s choices matter.\" You can…").opening)
    }

    private val essay = CoachPractice(kind = CoachKind.WRITING.name, title = "原创样本",
        material = "Directions: begins with the sentence “Libraries matter.” at least 150 words but no more than 200 words.",
        answer = "Libraries matter. Libraries helps students.")

    @Test fun writingReviewPromptCarriesFactsAndRefusesMismatchedFocus() {
        val prompt = WritingCoach.reviewPrompt(essay, WritingFocus.COMPLETE)
        assertTrue(prompt.contains("学生作文约 5 词") && prompt.contains("150–200 词") && prompt.contains("Libraries matter."))
        assertTrue(prompt.contains(essay.answer) && prompt.contains("\"type\":\"issue\"") && prompt.contains("rewrite"))
        assertFalse(WritingCoach.reviewPrompt(essay, WritingFocus.STRUCTURE).contains("最后输出 rewrite"))
        assertTrue(runCatching { WritingCoach.reviewPrompt(essay, WritingFocus.COVERAGE) }.isFailure)
        assertTrue(runCatching { WritingCoach.reviewPrompt(essay.copy(answer = ""), WritingFocus.COMPLETE) }.isFailure)
        assertTrue(WritingCoach.planPrompt(essay).contains("不要写出完整作文"))
    }

    @Test fun translationPromptNumbersSentencesAndHintsNeverAskForFullVersion() {
        val entry = CoachPractice(kind = CoachKind.TRANSLATION.name, material = "长江很长。它流经许多省份。", answer = "The Yangtze is long.")
        val prompt = TranslationCoach.reviewPrompt(entry, WritingFocus.COVERAGE)
        assertTrue(prompt.contains("[1] 长江很长。") && prompt.contains("[2] 它流经许多省份。") && prompt.contains("missing"))
        val hint = TranslationCoach.hintPrompt(entry, 2, SentenceHelp.STRUCTURE)
        assertTrue(hint.contains("[2] 它流经许多省份。") && hint.contains("不要写出完整译文"))
        assertTrue(runCatching { TranslationCoach.hintPrompt(entry, 3, SentenceHelp.TERMS) }.isFailure)
    }

    @Test fun readingPromptsUseTheSameParagraphNumbersAsTheReader() {
        val passage = ExamPassage("1", "", "First line wraps \nhere.  \nSecond paragraph.",
            listOf(ExamQuestion(46, "What wraps?", listOf("A", "B", "C", "D").map { ExamOption(it, "option $it") }, "A", "key p.1")))
        val prompt = ReadingCoach.explainPrompt(passage, "46. B", 46)
        assertTrue(prompt.contains("[1] First line wraps here.") && prompt.contains("[2] Second paragraph."))
        assertTrue(prompt.contains("学生选了 B，参考答案是 A"))
        assertTrue(ReadingCoach.paragraphPrompt(passage, 2, ParagraphHelp.SENTENCE).contains("[2] Second paragraph."))
        assertTrue(ReadingCoach.overviewPrompt(passage).contains("共 2 行"))
        val explained = ReadingExplanation.parse("""{"type":"locate","paragraph":1,"quote":"here.","note":"n"}
            {"type":"option","label":"b","verdict":"wrong","note":"偷换概念"}
            {"type":"check","question":"q","answer":"a"}""")
        assertEquals(1, explained.locate!!.paragraph)
        assertEquals("B", explained.options.single().label)
        assertEquals("a", explained.checkAnswer)
    }

    @Test fun chatPromptKeepsRecentTurnsWithinBudgetAndValidatesTurns() {
        val turns = (1..40).map { CoachTurn(practiceId = essay.id, anchor = Anchor.REVIEW, kind = TurnKind.CHAT,
            role = if (it % 2 == 0) CoachTurn.ASSISTANT else CoachTurn.USER, content = "第 $it 条" + "很长".repeat(200)) }
        val transcript = CoachTurns.transcript(turns, budget = 2000)
        assertTrue(transcript.contains("第 40 条") && !transcript.contains("第 1 条很"))
        val prompt = CoachChat.prompt(essay, Anchor.REVIEW, turns, "开头怎么改？")
        assertTrue(prompt.contains("开头怎么改？") && prompt.contains(essay.answer))
        assertTrue(runCatching { CoachChat.prompt(essay, "bogus", turns, "q") }.isFailure)
        assertTrue(Anchor.valid(Anchor.question(46)) && Anchor.valid(Anchor.sentence(3)) && !Anchor.valid("q:"))
        assertTrue(runCatching { CoachTurns.validate(turns.first().copy(kind = "other")) }.isFailure)
        assertEquals(listOf("为什么不能选 C？", "定位句再讲细一点", "这题属于哪种出题套路？", "出一道类似的题"),
            CoachChat.suggestions(CoachKind.READING, Anchor.question(47), "C"))
    }

    @Test fun acceptedFixesApplyOnlyWhereTheyWereLocated() {
        val review = CoachReview.parse("""{"type":"issue","quote":"helps","fix":"help","reason":"r","category":"语法","level":"must"}
            {"type":"issue","quote":"nowhere","fix":"x","reason":"r","category":"用词","level":"better"}""")
        val text = "Libraries helps students."
        val placed = com.example.cet6.ui.placeIssues(text, review, ReviewDecisions().accept(0).accept(1))
        assertNull(placed[1].range)
        assertEquals("Libraries help students.", com.example.cet6.ui.applyAccepted(text, placed))
    }
}
