package auto.parcel.sample.model2;

import com.google.gson.annotations.JsonAdapter;

import auto.parcel.AutoAdapter;
import auto.parcel.AutoParcel;

@AutoParcel
@AutoAdapter
@JsonAdapter(auto.parcel.sample.model2.AutoAdapter_Credentials.class)
public abstract class Credentials {

  public abstract int accountId();
  public abstract String apiKey();

  public static Builder builder() {
    return new AutoParcel_Credentials.Builder();
  }

  @AutoParcel.Builder
  public interface Builder {
    Builder accountId(int accountId);
    Builder apiKey(String apiKey);
    Credentials build();
  }

}
