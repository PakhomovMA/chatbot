package com.personal.chatbot.utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ThrowablesTest {

    @Test
    void findsTheDeepestCause() {
        Throwable root = new IOException("disk is full");
        Throwable wrapped = new IllegalStateException("indexing failed", new RuntimeException("write failed", root));
        assertThat(Throwables.rootCause(wrapped)).isSameAs(root);
        assertThat(Throwables.rootMessage(wrapped)).isEqualTo("disk is full");
    }

    @Test
    void fallsBackToTheClassNameWhenTheRootHasNoMessage() {
        assertThat(Throwables.rootMessage(new IllegalStateException(new IllegalArgumentException())))
                .isEqualTo("IllegalArgumentException");
    }

    @Test
    void detectsATypeAnywhereInTheChain() {
        Throwable chain = new IllegalStateException("outer", new IOException("inner"));
        assertThat(Throwables.anyCauseIs(chain, IOException.class)).isTrue();
        assertThat(Throwables.anyCauseIs(chain, InterruptedException.class)).isFalse();
    }

}
