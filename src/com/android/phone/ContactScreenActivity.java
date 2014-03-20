package com.android.phone;

import android.app.Activity;
import android.app.ActionBar;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.content.Intent;

import com.android.phone.R;

public class ContactScreenActivity extends Activity {
    private static final String TAG = "ContactScreenActivity";
    private static final String UP_ACTIVITY_PACKAGE = "com.android.contacts";
    private static final String UP_ACTIVITY_CLASS =
            "com.android.contacts.activities.PeopleActivity";

    private String mName, mNewName;
    private String mPhoneNumber, mNewPhoneNumber;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.contact_screen);

        try {
            final EditText editName = (EditText) this.findViewById(R.id.name);
            final EditText editPhoneNumber = (EditText) this.findViewById(R.id.phoneNumber);
            final Intent intent = getIntent();
            mName = intent.getStringExtra("NAME");
            mPhoneNumber = intent.getStringExtra("PHONE");

            editName.setText(mName, TextView.BufferType.EDITABLE);
            editPhoneNumber.setText(mPhoneNumber, TextView.BufferType.EDITABLE);

            View.OnClickListener handler = new View.OnClickListener(){
                public void onClick(View v) {
                    switch (v.getId()){
                        case R.id.save:
                            mNewName = editName.getText().toString();
                            mNewPhoneNumber = editPhoneNumber.getText().toString();
                            Log.d(TAG, "Name: " + mName + " Number: "
                                    + mPhoneNumber);
                            Log.d(TAG, " After edited Name: "
                                    + mNewName + " Number: " + mNewPhoneNumber);
                            Intent intent = new Intent();
                            intent.putExtra("NAME", mName);
                            intent.putExtra("PHONE", mPhoneNumber);
                            intent.putExtra("NEWNAME", mNewName);
                            intent.putExtra("NEWPHONE", mNewPhoneNumber);
                            setResult(RESULT_OK, intent);
                            finish();
                            break;
                        case R.id.cancel:
                            finish();
                            break;
                    }
                }
            };

            findViewById(R.id.save).setOnClickListener(handler);
            findViewById(R.id.cancel).setOnClickListener(handler);

        } catch(Exception e){
            Log.e(TAG, e.toString());
        }

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                Intent intent = new Intent();
                intent.setClassName(UP_ACTIVITY_PACKAGE, UP_ACTIVITY_CLASS);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                finish();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
