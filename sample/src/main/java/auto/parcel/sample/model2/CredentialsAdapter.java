package auto.parcel.sample.model2;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public class CredentialsAdapter extends TypeAdapter<Credentials> {

  @Override public void write(JsonWriter out, Credentials credentials) throws IOException {
    out.beginObject();
    out.name("accountId").value(credentials.accountId());
    out.name("apiKey").value(credentials.apiKey());
    out.endObject();
  }

  @Override public Credentials read(JsonReader in) throws IOException {
    Credentials.Builder builder = Credentials.builder();
    in.beginObject();
    while (in.hasNext()) {
      switch (in.nextName()) {
        case "accountId":
          builder.accountId(in.nextInt());
          break;
        case "apiKey":
          builder.apiKey(in.nextString());
          break;
      }
    }
    in.endObject();
    return builder.build();
  }
}
