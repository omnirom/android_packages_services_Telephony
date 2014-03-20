package com.android.phone;

import android.app.Activity;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.widget.TextView;

import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;

import static android.view.Window.PROGRESS_VISIBILITY_OFF;
import static android.view.Window.PROGRESS_VISIBILITY_ON;

import com.android.phone.R;

public class ExportContactsToSim extends Activity {
    private static final String TAG = "ExportContactsToSim";
    private static final String UP_ACTIVITY_PACKAGE = "com.android.contacts";
    private static final String UP_ACTIVITY_CLASS =
            "com.android.contacts.activities.PeopleActivity";

    private TextView mEmptyText;
    private int mResult = 1;

    private static final int CONTACTS_EXPORTED = 1;
    private static final String[] COLUMN_NAMES = new String[] {
            "name",
            "number",
            "emails"
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().requestFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.export_contact_screen);
        mEmptyText = (TextView) findViewById(R.id.exportempty);
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        doExportToSim();
    }

    private void doExportToSim() {

        displayProgress(true);

        new Thread(new Runnable() {
            public void run() {
                //Local adnList will be empty till query the SIM contacts
                //So unable to export phone contacts to SIM.
                //Before export contacts to SIM need to query SIM contacts.
                Uri uri = getUri();
                if (uri == null)  return;
                Cursor simContactsCur = getContentResolver().query(uri,
                        COLUMN_NAMES, null, null, null);

                Cursor contactsCursor = getContactsContentCursor();
                for (int i=0; contactsCursor.moveToNext(); i++) {
                    String id = getContactIdFromCursor(contactsCursor);
                    Cursor dataCursor = getDataCursorRelatedToId(id);
                    populateContactDataFromCursor(dataCursor );
                    dataCursor.close();
                }
                contactsCursor.close();
                Message message = Message.obtain(mHandler, CONTACTS_EXPORTED, (Integer)mResult);
                mHandler.sendMessage(message);
            }
        }).start();
    }

    private Cursor getContactsContentCursor() {
        Uri phoneBookContentUri = ContactsContract.Contacts.CONTENT_URI;
        String recordsWithPhoneNumberOnly = ContactsContract.Contacts.HAS_PHONE_NUMBER
                + "='1'";

        Cursor contactsCursor = managedQuery(phoneBookContentUri, null,
                recordsWithPhoneNumberOnly, null, null);
        return contactsCursor;
    }

    private String getContactIdFromCursor(Cursor contactsCursor) {
        String id = contactsCursor.getString(contactsCursor
                .getColumnIndex(ContactsContract.Contacts._ID));
        return id;
    }

    private Cursor getDataCursorRelatedToId(String id) {
        String where = ContactsContract.Data.CONTACT_ID + " = " + id;


        Cursor dataCursor = getContentResolver().query(
                ContactsContract.Data.CONTENT_URI, null, where, null, null);
        return dataCursor;
    }

    private void populateContactDataFromCursor(final Cursor dataCursor) {
        Uri uri = getUri();
        if (uri == null) {
            Log.d(TAG," populateContactDataFromCursor: uri is null, return ");
            return;
        }
        Uri contactUri;
        int nameIdx = dataCursor
                .getColumnIndex(ContactsContract.Data.DISPLAY_NAME);
        int phoneIdx = dataCursor
                .getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);

        if (dataCursor.moveToFirst()) {
            // Extract the name.
            String name = dataCursor.getString(nameIdx);
            // Extract the phone number.
            String rawNumber = dataCursor.getString(phoneIdx);
            String number = PhoneNumberUtils.formatNumber(rawNumber);
            ContentValues values = new ContentValues();
            values.put("tag", name);
            values.put("number", number);
            Log.d("ExportContactsToSim", "name : " + name + " number : " + number);
            contactUri = getContentResolver().insert(uri, values);
            if (contactUri == null) {
                Log.e("ExportContactsToSim", "Failed to export contact to SIM for " +
                        "name : " + name + " number : " + number);
                mResult = 0;
            }
        }
    }

    private void showAlertDialog(String value) {
        AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle("Result...");
        alertDialog.setMessage(value);
        alertDialog.setButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                // finish contacts activity
                finish();
            }
        });
        alertDialog.show();
    }

    private void displayProgress(boolean loading) {
        mEmptyText.setText(R.string.exportContacts);
        getWindow().setFeatureInt(
                Window.FEATURE_INDETERMINATE_PROGRESS,
                loading ? PROGRESS_VISIBILITY_ON : PROGRESS_VISIBILITY_OFF);
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case CONTACTS_EXPORTED:
                    int result = (Integer)msg.obj;
                    if (result == 1) {
                        showAlertDialog(getString(R.string.exportAllcontatsSuccess));
                    } else {
                        showAlertDialog(getString(R.string.exportAllcontatsFailed));
                    }
                    break;
            }
        }
    };

    private Uri getUri() {
        return Uri.parse("content://icc/adn");
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
