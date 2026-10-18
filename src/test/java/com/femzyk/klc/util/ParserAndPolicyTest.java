package com.femzyk.klc.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParserAndPolicyTest {

    @Test void markedOptionsAndTopic() {
        var qs = QuestionTextParser.parse(
            "Question: What is 2 + 2?\nTopic: Addition\n[x] 4\n[ ] 22\n[ ] 6\n");
        assertEquals(1, qs.size());
        assertEquals("Addition", qs.get(0).topic());
        assertEquals("MCQ", qs.get(0).type());
        assertEquals(3, qs.get(0).options().size());
        assertTrue(qs.get(0).options().get(0).correct());
        assertEquals(1, qs.get(0).correctCount());
    }

    @Test void unprefixedQuestionIsNeverDropped() {
        var qs = QuestionTextParser.parse(
            "What is the capital\nof Nigeria?\n[ ] Lagos\n[x] Abuja\n");
        assertEquals(1, qs.size());
        assertEquals("What is the capital of Nigeria?", qs.get(0).text());
        assertTrue(qs.get(0).options().get(1).correct());
    }

    @Test void numberedQuestionsWithLetteredOptionsAndAnswerKey() {
        var qs = QuestionTextParser.parse(
            "1. Which gas do plants absorb?\nA. Oxygen\nB. Carbon dioxide\nAnswer: B\n\n" +
            "2. H2O is?\nA. Salt\nB. Water\nAnswer: B\n");
        assertEquals(2, qs.size());
        assertTrue(qs.get(0).options().get(1).correct());
        assertEquals("Water", qs.get(1).options().get(1).text());
    }

    @Test void trueFalseTyping() {
        var qs = QuestionTextParser.parse("Question: The sun rises in the west.\n[x] False\n[ ] True\n");
        assertEquals("TRUE_FALSE", qs.get(0).type());
    }

    @Test void structuredDetectionAndEmpty() {
        assertTrue(QuestionTextParser.looksStructured("[x] 4\n[ ] 5"));
        assertFalse(QuestionTextParser.looksStructured("just a line of text"));
        assertEquals(List.of(), QuestionTextParser.parse(""));
        assertEquals(List.of(), QuestionTextParser.parse(null));
    }

    @Test void verifyCodesAreDeterministicAndTamperEvident() {
        String a = VerifyCodes.forResult("stu-1", "att-1", 72.5, "secret");
        assertEquals(a, VerifyCodes.forResult("stu-1", "att-1", 72.5, "secret"));
        assertTrue(a.startsWith("KLC-"));
        assertTrue(VerifyCodes.matches(a, "stu-1", "att-1", 72.5, "secret"));
        assertFalse(VerifyCodes.matches(a, "stu-1", "att-1", 73.0, "secret"));
        assertFalse(VerifyCodes.matches(a, "stu-1", "att-1", 72.5, "wrong-secret"));
    }

    @Test void retentionPolicy() {
        assertEquals(2024, RetentionPolicy.sessionStartYear("2024/2025"));
        assertEquals(-1, RetentionPolicy.sessionStartYear("junk"));
        assertTrue(RetentionPolicy.resultsArchiveDue("2010/2011", 2026));
        assertFalse(RetentionPolicy.resultsArchiveDue("2024/2025", 2026));
        assertTrue(RetentionPolicy.personalAnonymiseDue(2020, 2026));
        assertFalse(RetentionPolicy.personalAnonymiseDue(2024, 2026));
        assertTrue(RetentionPolicy.auditPurgeDue(2018, 2026));
        assertFalse(RetentionPolicy.auditPurgeDue(2024, 2026));
    }

    @Test void attemptPolicy() {
        assertTrue(AttemptPolicy.canStart(1, 0, false));
        assertFalse(AttemptPolicy.canStart(1, 1, false));
        assertFalse(AttemptPolicy.canStart(2, 2, false));
        assertTrue(AttemptPolicy.canStart(2, 1, false));
        assertTrue(AttemptPolicy.canStart(1, 99, true));   // practice unlimited
        assertTrue(AttemptPolicy.canStart(0, 99, false));  // unconfigured = unlimited
        assertTrue(AttemptPolicy.denialMessage(1, 1).contains("Attempt limit reached"));
    }
}
