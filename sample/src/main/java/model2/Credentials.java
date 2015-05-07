package model2;

import com.google.gson.annotations.JsonAdapter;

import auto.parcel.AutoAdapter;
import auto.parcel.AutoParcel;
import model2.Credentials$Adapter;

@AutoParcel
@AutoAdapter @JsonAdapter(Credentials$Adapter.class)
public abstract class Credentials {

  public abstract int accountId();
  public abstract String apiKey();

  public static Builder builder() {
    return new AutoParcel_Credentials.Builder();
  }

  @AutoParcel.Builder
  public interface Builder {
    public Builder accountId(int accountId);
    public Builder apiKey(String apiKey);
    public Credentials build();
  }

}
