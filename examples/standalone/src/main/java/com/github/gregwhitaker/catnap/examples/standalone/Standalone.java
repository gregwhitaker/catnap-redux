package com.github.gregwhitaker.catnap.examples.standalone;

import com.github.gregwhitaker.catnap.core.Catnap;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import com.github.gregwhitaker.catnap.streams.NdjsonReader;
import com.github.gregwhitaker.catnap.streams.NdjsonWriter;
import java.io.InputStream;
import java.io.OutputStream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Run without arguments for tree reuse, or with `ndjson QUERY [CATNAP|GOOGLE|LINKEDIN]` to stream
 * stdin. The default dialect is CATNAP.
 */
public final class Standalone {
  private Standalone() {}

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("ndjson")) {
      transform(
          System.in,
          System.out,
          args[1],
          args.length > 2 ? QueryDialect.valueOf(args[2]) : QueryDialect.CATNAP);
      return;
    }
    var json = new Jackson3Json(JsonMapper.builder().build());
    var document =
        json.parse("{\"name\":\"ACME\",\"price\":{\"amount\":12.50,\"currency\":\"USD\"}}");
    for (String fields : new String[] {"name", "price(amount)"}) {
      System.out.println(
          json.write(
              Catnap.compile(fields, QueryDialect.CATNAP).evaluate(document, json.adapter())));
    }
  }

  /** Pull/emit records without collecting the stream. Does not close caller-owned streams. */
  public static void transform(
      InputStream input, OutputStream output, String expression, QueryDialect dialect)
      throws Exception {
    var query = Catnap.compile(expression, dialect);
    var json = new Jackson3Json(JsonMapper.builder().build());
    var reader = new NdjsonReader(input, 1_048_576);
    var writer = new NdjsonWriter(output, 1_048_576);
    String record;
    while ((record = reader.nextRecord()) != null) {
      writer.writeRecord(json.write(query.evaluate(json.parse(record), json.adapter())));
    }
  }
}
