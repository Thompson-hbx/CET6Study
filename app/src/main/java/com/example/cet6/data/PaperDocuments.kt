package com.example.cet6.data

/** Source filenames retain their published set labels, including the March combined PDF. */
object PaperDocuments {
    /** Combined source PDF pages 1/3 omit reading and refer to set 1; no order mapping is supplied. */
    fun sharedReadingPaperId(paper: ExamPaper): String? =
        if (!paper.sample && paper.examDate == "2023-03-12" &&
            paper.setCode in listOf("2", "3") && paper.passages.isEmpty())
            ExamCatalog.paperId(paper.examDate, "1")
        else null

    fun filenames(paper: ExamPaper): List<String> {
        if (paper.sample) return emptyList()
        val month = paper.examDate.substring(0, 7).replace('-', '_')
        val set = if (month == "2023_03" && paper.setCode in listOf("2", "3")) "2-3" else paper.setCode
        val stem = "cet6_${month}_$set"
        return listOf("$stem.pdf", "${stem}_ans.pdf")
    }

    fun validFilename(name: String) = Regex("""cet6_\d{4}_\d{2}_[123](?:-3)?(?:_ans)?\.pdf""").matches(name)
}
