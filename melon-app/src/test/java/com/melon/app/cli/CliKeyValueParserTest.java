package com.melon.app.cli;

import com.melon.app.cli.spec.CliKeyValueParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CliKeyValueParserTest {

    @Test
    void parsesPrimitiveAndJsonValues() {
        Map<String, Object> values = CliKeyValueParser.parsePairs(List.of(
                "enabled=true",
                "retries=3",
                "temperature=0.7",
                "tags=[\"cli\",\"test\"]",
                "name=default"
        ));

        assertEquals(true, values.get("enabled"));
        assertEquals(3, values.get("retries"));
        assertEquals(0.7, values.get("temperature"));
        assertInstanceOf(List.class, values.get("tags"));
        assertEquals("default", values.get("name"));
    }
}
