package com.personal.chatbot;

import org.springframework.boot.test.context.SpringBootTest;

/** The same repeated-dialog scenario with serving enabled instead of shadow. */
@SpringBootTest(properties = {"chatbot.cache.derivation.shadow=false", "chatbot.cache.derivation.enabled=true"})
class DerivationCacheE2eTest extends DerivationShadowE2eTest {
    @Override
    protected boolean serving() { return true; }
}
