package org.jetlinks.community.configuration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.jetlinks.community.utils.DateMathParser;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Date;

/** Local dates and date-math expressions use Beijing time; explicit offsets retain their instant. */
public class ShanghaiDateDeserializer extends JsonDeserializer<Date> {
    @Override
    public Date deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return new Date(parser.getLongValue());
        }
        String text = parser.getValueAsString();
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return new Date(DateMathParser.parse(text.trim(), System::currentTimeMillis, ZoneId.of("Asia/Shanghai")));
        } catch (RuntimeException error) {
            throw context.weirdStringException(text, Date.class, "Invalid date or date-math expression");
        }
    }
}
