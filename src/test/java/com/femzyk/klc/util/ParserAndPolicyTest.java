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
