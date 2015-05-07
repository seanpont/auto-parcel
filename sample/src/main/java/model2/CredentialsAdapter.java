package model2;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public class CredentialsAdapter extends TypeAdapter<Credentials> {

  @Override public void write(JsonWriter out, Credentials credentials) throws IOException {
    // implement write: combine firstName and lastName into name
    out.beginObject();
    out.name("accountId").value(credentials.accountId());
    out.name("apiKey").value(credentials.apiKey());
    out.endObject();
  }

  @Override public Credentials read(JsonReader in) throws IOException {
    Credentials.Builder builder = Credentials.builder();
    in.beginObject();
    while (in.hasNext()) {
      String name = in.nextName();
      if (name.equals("accountId")) {
        builder.accountId(in.nextInt());
      } else if (name.equals("apiKey")) {
        builder.apiKey(in.nextString());
      }
    }
    in.endObject();
    return builder.build();
  }
}
