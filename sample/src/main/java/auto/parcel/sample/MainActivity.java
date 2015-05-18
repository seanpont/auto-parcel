package auto.parcel.sample;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import auto.parcel.sample.model2.Credentials;

public class MainActivity extends Activity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    findViewById(R.id.click_me).setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        Intent detailIntent = new Intent(MainActivity.this, DetailActivity.class);
        detailIntent.putExtra("Person", SampleData.BOB);
        startActivity(detailIntent);
      }
    });
    Credentials c = Credentials.builder().accountId(1234).apiKey("1234").build();
    System.out.println(c);
  }
}
