package com.grzwolf.grznote;
 
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Timer;
import java.util.TimerTask;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

// credits: app based on https://www.thecrazyprogrammer.com/2015/08/simple-notepad-app-android-example.html
public class MainActivity extends Activity {

    // UI controls to interact with
    Button   exitButton, saveButton, openButton;
    EditText textEditor; // initially text edit is disabled via android:enabled="false" in layout xml

    // dlg shall be dismissed, if going to pause
    AlertDialog alertSaveChanges = null;

    // status vars
    boolean  textEditorChanged = false;
    boolean  storageFileIsOpen = false;
    long     storageFileSize = 0;
    boolean  appIsHardClosing = false;
    boolean  wentThruOnCreate = false;
    String   textEditorPauseContent = "";

    // indicate editor dirty flag as button text color
    void setTextEditorChanged(boolean hasChanged) {
        saveButton.setTextColor(hasChanged ? Color.RED : Color.BLACK);
        textEditorChanged = hasChanged;
    }

    // pwd TIMEOUT timer: 2min = 120s = 120.000ms
    static final long PWD_TIMEOUT = 120000;
    Timer pwdTimer = null;

    // password
    String difficultPassword = "";

    // 'password error' getter & setter organize its handling
    int errorCounterPassword = 0;
    public int getErrorCounterPassword() {
        return errorCounterPassword;
    }
    public void setErrorCounterPassword(int counter) {
        errorCounterPassword = counter;
        // > 5 pwd errors
        if ( errorCounterPassword > 5 ) {
            // now there are three choices ...
            AlertDialog.Builder adYesNoCont = new AlertDialog.Builder(this);
            adYesNoCont.setMessage("Error password > 5\n\nStart from scratch with a new data file and delete the old data file?");
            // ... 1) option to delete the data storage file encrypted with an unknown password and restart app
            adYesNoCont.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // 'are you sure'
                    AlertDialog.Builder builder = null;
                    builder = new AlertDialog.Builder(MainActivity.this);
                    builder.setTitle("Reset GrzNote");
                    builder.setMessage("\nAre you sure?");
                    builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            appIsHardClosing = true;
                            resetRestartApp();
                        }
                    });
                    builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
                    AlertDialog alert = builder.create();
                    alert.show();
                    alert.setCanceledOnTouchOutside(false);
                }
            });
            // ... 2) simply go ahead with reminder
            adYesNoCont.setNegativeButton("No", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });
            // ... 3) five more tries without reminder
            adYesNoCont.setNeutralButton("next 5 tries", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    setErrorCounterPassword(0);
                }
            });
            AlertDialog alert = adYesNoCont.create();
            alert.show();
            alert.setCanceledOnTouchOutside(false);
        }
    }

    // app lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        this.setTitle("GrzNote " + BuildConfig.VERSION_NAME);

        // a flag is needed to distinguish, whether the app is really started OR just resumed
        wentThruOnCreate = true;

        // active controls
        exitButton = (Button) findViewById(R.id.exitButton);
        saveButton = (Button) findViewById(R.id.saveButton);
        openButton = (Button) findViewById(R.id.openButton);
        textEditor = (EditText) findViewById(R.id.text);

        // text editor change listener
        textEditor.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                // simple text editor change/dirty flag
                setTextEditorChanged(true);
                // user interaction happened
                recordUserActivityTime();
            }
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        // text editor touch listener
        textEditor.setOnTouchListener(new View.OnTouchListener() {
            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if ( event.getAction() == MotionEvent.ACTION_DOWN ) {
                    // user interaction happened
                    if ( textEditor.isEnabled() ) {
                        recordUserActivityTime();
                    }
                }
                return false;
            }
        });

        // initially start with file open dlg
        openButton.performClick();

        // friendly reminder
        dontShowAgain("Your password is not stored anywhere.",
                new CharSequence[] {"Don't show again", "Show again"},
                "DontShowAgain");
    }
    @Override
    protected void onResume() {
        if ( wentThruOnCreate ) {
            // only get here after fresh app start: once this is fact, there is no need anymore to keep the flag true
            wentThruOnCreate = false;
        } else {
            // only get here after a pause --> resume: check whether pwd TIMEOUT is due
            checkPwdTimeout(true);
        }
        super.onResume();
    }
    @Override
    protected void onPause() {
        // two cases when coming here:
        if ( appIsHardClosing ) {
            // hard close app after regular close button OR reset app after too many pwd fails
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor edit = preferences.edit();
            edit.putLong("LatestUserActivityTime", -1);
            edit.apply();
        } else {
            // if editor text was changed, save it temporarily (will be later handled in onResume / checkPwdTimeout, if pwd is not expired)
            textEditorPauseContent = "";
            if ( textEditorChanged ) {
                textEditorPauseContent = textEditor.getText().toString();
            }
            // make editor text disappear from view in recent apps
            textEditor.setText("");
            setTextEditorChanged(false);
            // simple pause shall record the time when it happens to allow a password expire TIMEOUT
            recordUserActivityTime();
        }
        super.onPause();
    }

    // record time of latest user activity
    public void recordUserActivityTime() {
        long time = System.currentTimeMillis();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = preferences.edit();
        edit.putLong("LatestUserActivityTime", time);
        edit.apply();
    }

    // pwd timer
    void startPwdTimer() {
        if ( pwdTimer != null ) {
            return;
        }
        pwdTimer = new Timer();
        pwdTimer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                checkPwdTimeout(false);
            }
        }, 500, 10000);
    }

    // check whether the TIMEOUT has expired; openFile flag allows to open the storage file, if there is no pwd timeout
    public void checkPwdTimeout(boolean openFile) {
        // in case, there is now valid pwd, simply return
        if ( difficultPassword.length() == 0 ) {
            return;
        }
        // get time when the there was the last user activity, credits: https://stackoverflow.com/questions/576600/lock-android-app-after-a-certain-amount-of-idle-time
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        long pausedStart = preferences.getLong("LatestUserActivityTime", -1);
        if ( pausedStart == -1 ) {
            return;
        }
        // avoid confusion after onPause --> onResume with a previously open 'save changes dialog'
        if ( alertSaveChanges != null && alertSaveChanges.isShowing() && textEditorPauseContent.length() > 0 ) {
            alertSaveChanges.cancel();
        }
        // password TIMEOUT check
        if ( System.currentTimeMillis() - pausedStart >= PWD_TIMEOUT ) {
            // lock password & file status settings
            resetPwd();
        } else {
            // show text editor text again after pause: this path only happens after pause --> resume with no pwd timeout
            if ( openFile ) {
                // 'textEditorPauseContent.length() > 0' indicates, that there had been text changes before onPause
                if ( textEditorPauseContent.length() > 0 ) {
                    textEditor.setText(textEditorPauseContent);
                    textEditorPauseContent = "";
                    setTextEditorChanged(true);
                    storageFileIsOpen = true;
                    textEditorSetEnabled(true);
                } else {
                    // otherwise simply re open storage file
                    openButton.performClick();
                }
            }
        }
    }

    // reset: pwd timer, pwd, text editor content, file status + give message
    void resetPwd() {
        try {
            // method is called from timer thread, therefore "runOnUiThread" is essential (otherwise foreign thread exception)
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // stop pwd timer
                    pwdTimer.cancel();
                    pwdTimer = null;
                    // avoid potential confusion after restart pwd timer
                    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                    SharedPreferences.Editor edit = preferences.edit();
                    edit.putLong("LatestUserActivityTime", -1);
                    edit.apply();
                    // reset pwd
                    difficultPassword = "";
                    // reset text editor
                    textEditor.setText("");
                    setTextEditorChanged(false);
                    textEditorSetEnabled(false);
                    // reset file status
                    storageFileIsOpen = false;
                    storageFileSize = 0;
                    // fire open file dlg (will ask for pwd, if ok starts pwd timer)
                    openButton.performClick();
                    // put TIMEOUT info on top of file open procedure
                    okBox("Note", "Timeout > 2 minutes with no user activity.\n\nUnsaved changes are lost.");
                }
            });
        } catch (Exception e) {
            okBox("Exception", e.getMessage());
        }
    }

    // delete the data storage file encrypted with an unknown password and restart app
    void resetRestartApp() {
        // reset don't show again
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor spe = sharedPref.edit();
        spe.putBoolean("DontShowAgain", false);
        spe.commit();
        // delete the data storage file encrypted with an unknown password
        String storagePath = MainActivity.this.getExternalFilesDir(null).getAbsolutePath();
        String appName = getApplicationInfo().loadLabel(getPackageManager()).toString();
        String storageFileName = storagePath + "/" + appName;
        File file = new File(storageFileName);
        file.delete();
        // restart app from scratch, credits: https://stackoverflow.com/questions/46070938/restarting-android-app-programmatically/71392776#71392776
        try {
            Context ctx = getApplicationContext();
            PackageManager pm = ctx.getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(ctx.getPackageName());
            Intent mainIntent = Intent.makeRestartActivityTask(intent.getComponent());
            ctx.startActivity(mainIntent);
            System.exit(0);
        } catch ( Exception e) {
            okBox("Restart GrzNote", "Start it from apps folder manually.");
        }
    }

    // all user action other than editing text takes place here
    public void buttonAction(View v) {

        final EditText pwdEditor = new EditText(this);
        AlertDialog.Builder ad = new AlertDialog.Builder(this);
        ad.setView(pwdEditor);

        // toggle: edit mode <--> save text from editor to file
        if ( v.getId() == R.id.saveButton ) {

            // user interaction
            recordUserActivityTime();

            // if editor is not yet enabled, ask whether to enable it
            if ( !textEditor.isFocusable() ) {
                // only if file is open, toggle button text from "Edit" to "Save" and enable edit mode
                if ( storageFileIsOpen ) {
                    AlertDialog.Builder adYesNo = new AlertDialog.Builder(this);
                    adYesNo.setMessage("This will enable the edit mode.\n\nContinue?");
                    adYesNo.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            textEditorSetEnabled(true);
                        }
                    });
                    adYesNo.setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                        }
                    });
                    AlertDialog alert = adYesNo.create();
                    alert.show();
                    alert.setCanceledOnTouchOutside(false);
                }
                return;
            }

            // avoid accidental override of a not empty data file
            if ( !storageFileIsOpen && storageFileSize > 0 ) {
                okBox("Error data file",
                        "The data file was not yet opened and contains data.\n\nOpen the file first.");
                return;
            }

            // nothing to save, just re open file
            if ( !textEditorChanged ) {
                openButton.performClick();
                return;
            }

            // for the sake of mind
            AlertDialog.Builder adYesNoCancel = new AlertDialog.Builder(this);
            adYesNoCancel.setMessage("The current text will be saved to the data file.\n\nYes       = save changes\nCancel = continue edit\nNo         = discard changes");
            adYesNoCancel.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // user interaction happened
                    recordUserActivityTime();
                    // if there is no pwd, ask for it and use it
                    if ( difficultPassword.length() == 0 ) {
                        ad.setMessage("Type password");
                        ad.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // read pwd from pwd input dlg
                                difficultPassword = pwdEditor.getText().toString();
                                // make pwd somehow reasonable
                                difficultPassword = pwdModder(difficultPassword);
                                // get clear text
                                String clearTextString = textEditor.getText().toString();
                                // saving an empty text could be intentional, mostly isn't
                                if ( clearTextString.length() == 0 ) {
                                    AlertDialog.Builder adYesNo = new AlertDialog.Builder(MainActivity.this);
                                    adYesNo.setMessage("Current text is empty.\n\nYou sure to continue anyway?");
                                    adYesNo.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            try {
                                                encryptStorageFile(clearTextString, difficultPassword);
                                                openButton.performClick();
                                            } catch (IOException |
                                                    NoSuchAlgorithmException |
                                                    NoSuchPaddingException |
                                                    InvalidKeyException exc) {
                                                difficultPassword = "";
                                                okBox("Error", exc.getMessage());
                                            }
                                        }
                                    });
                                    adYesNo.setNegativeButton("No", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            dialog.cancel();
                                        }
                                    });
                                    AlertDialog alert = adYesNo.create();
                                    alert.show();
                                    alert.setCanceledOnTouchOutside(false);
                                } else {
                                    try {
                                        encryptStorageFile(clearTextString, difficultPassword);
                                        openButton.performClick();
                                    } catch (IOException |
                                            NoSuchAlgorithmException |
                                            NoSuchPaddingException |
                                            InvalidKeyException exc) {
                                        difficultPassword = "";
                                        okBox("Error", exc.getMessage());
                                    }
                                }
                            }
                        });
                        ad.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                difficultPassword = "";
                                dialog.cancel();
                            }
                        });
                        AlertDialog alert = ad.create();
                        alert.show();
                        alert.setCanceledOnTouchOutside(false);
                        // repeat welcome screen on top of the pwd dlg only at first usage
                        firstDataUsage();
                    } else {
                        // if pwd is somehow ok, use it
                        String clearTextString = textEditor.getText().toString();
                        // empty text check
                        if ( clearTextString.length() == 0 ) {
                            AlertDialog.Builder adYesNo = new AlertDialog.Builder(MainActivity.this);
                            adYesNo.setMessage("Current text is empty. Continue anyway?");
                            adYesNo.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    try {
                                        encryptStorageFile(clearTextString, difficultPassword);
                                        openButton.performClick();
                                    } catch (IOException |
                                            NoSuchAlgorithmException |
                                            NoSuchPaddingException |
                                            InvalidKeyException exc) {
                                        difficultPassword = "";
                                        okBox("Error", exc.getMessage());
                                    }
                                }
                            });
                            adYesNo.setNegativeButton("No", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.cancel();
                                }
                            });
                            AlertDialog alert = adYesNo.create();
                            alert.show();
                            alert.setCanceledOnTouchOutside(false);
                        } else {
                            try {
                                encryptStorageFile(clearTextString, difficultPassword);
                                openButton.performClick();
                            } catch (IOException |
                                    NoSuchAlgorithmException |
                                    NoSuchPaddingException |
                                    InvalidKeyException exc) {
                                difficultPassword = "";
                                okBox("Error", exc.getMessage());
                            }
                        }
                    }
                }
            });
            adYesNoCancel.setNegativeButton("No", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    openButton.performClick();
                    dialog.cancel();
                }
            });
            adYesNoCancel.setNeutralButton("Cancel", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });
            alertSaveChanges = adYesNoCancel.create();
            alertSaveChanges.show();
            alertSaveChanges.setCanceledOnTouchOutside(false);
        }

        // decrypted file open handling
        if( v.getId() == R.id.openButton ) {
            // user interaction happened
            recordUserActivityTime();
            // if there is no pwd, ask for pwd, open file, decrypt it, show it as clear text in editor
            if ( difficultPassword.length() == 0 ) {
                ad.setMessage("Type password");
                ad.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        difficultPassword = pwdEditor.getText().toString();
                        difficultPassword = pwdModder(difficultPassword);
                        try {
                            // read from encrypted file, decrypt date, show them
                            String clearText = decryptStorageFile(difficultPassword);
                            setErrorCounterPassword(0);
                            textEditor.setText(clearText);
                            // clear editor dirty flag after setting the data to editor from file
                            setTextEditorChanged(false);
                            // data storage file is now open
                            storageFileIsOpen = true;
                            // disable edit mode after file open & toggle 'Save/Edit' button text to "Edit"
                            textEditorSetEnabled(false);
                            // pwd TIMEOUT timer
                            startPwdTimer();
                        } catch (IOException |
                                NoSuchAlgorithmException |
                                NoSuchPaddingException |
                                InvalidKeyException exc) {
                            setErrorCounterPassword(getErrorCounterPassword() + 1);
                            difficultPassword = "";
                            okBox("Error Password", "Something went wrong.");
                        }
                    }
                });
                ad.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        difficultPassword = "";
                        dialog.cancel();
                    }
                });
                AlertDialog alert = ad.create();
                alert.show();
                alert.setCanceledOnTouchOutside(false);
                // repeat welcome screen on the top of the pwd dlg at first usage
                firstDataUsage();
            } else {
                // there is a pwd and the text editor was previously changed: ask about potential data loss from editor to file
                if ( textEditorChanged ) {
                    AlertDialog.Builder adYesNo = new AlertDialog.Builder(this);
                    adYesNo.setMessage("Unsaved changes will be lost. Continue?");
                    adYesNo.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                String clearText = decryptStorageFile(difficultPassword);
                                textEditor.setText(clearText);
                                setTextEditorChanged(false);
                                storageFileIsOpen = true;
                                textEditorSetEnabled(false);
                            } catch (IOException |
                                    NoSuchAlgorithmException |
                                    NoSuchPaddingException |
                                    InvalidKeyException exc) {
                                difficultPassword = "";
                                okBox("Error Password", "Something went wrong.");
                            }
                        }
                    });
                    adYesNo.setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                        }
                    });
                    AlertDialog alert = adYesNo.create();
                    alert.show();
                    alert.setCanceledOnTouchOutside(false);
                } else {
                    // there is a pwd and the text editor has no changes: simply open the decrypted file and show it
                    try {
                        String clearText = decryptStorageFile(difficultPassword);
                        textEditor.setText(clearText);
                        setTextEditorChanged(false);
                        storageFileIsOpen = true;
                        textEditorSetEnabled(false);
                    } catch (IOException |
                            NoSuchAlgorithmException |
                            NoSuchPaddingException |
                            InvalidKeyException exc) {
                        difficultPassword = "";
                        okBox("Error Password", "Something went wrong.");
                    }
                }
            }
        }

        // close app - ask whether to save or not
        if ( v.getId() == R.id.exitButton ) {
            // user interaction happened
            recordUserActivityTime();
            // for the sake of mind
            AlertDialog.Builder adYesNo = new AlertDialog.Builder(this);
            String text = textEditorChanged ? "This will fully close the app." +
                    "\n\nUnsaved data will be lost." : "This will fully close the app.";
            adYesNo.setMessage(text + "\n\nContinue?");
            adYesNo.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    appIsHardClosing = true;
                    finishAndRemoveTask();
                }
            });
            adYesNo.setNegativeButton("No", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });
            AlertDialog alert = adYesNo.create();
            alert.show();
            alert.setCanceledOnTouchOutside(false);
        }
    }

    // switch edit mode for textEditor
    void textEditorSetEnabled(boolean enable) {
        if ( enable ) {
            saveButton.setText("Save");
            textEditor.setFocusableInTouchMode(true);
            textEditor.setKeyListener(new EditText(getApplicationContext()).getKeyListener());
            textEditor.setFocusable(true);
            textEditor.setCursorVisible(true);
            textEditor.requestFocus();
            textEditor.getBackground().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP);
        } else {
            saveButton.setText("Edit");
            textEditor.setFocusableInTouchMode(false);
            textEditor.clearFocus();
            textEditor.setKeyListener(null);
            textEditor.setFocusable(false);
            textEditor.setCursorVisible(false);
            textEditor.getBackground().setColorFilter(Color.YELLOW, PorterDuff.Mode.SRC_ATOP);
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(textEditor.getWindowToken(), 0);
        }
    }

    // extend password to 16 chars
    String pwdModder(String password) {
        // if pwd is empty, return a definitely failing pwd instead
        if ( password.length() == 0 ) {
            return "0";
        }
        // pwd length is too short OR too long OR length is ok
        if ( password.length() < 4 || password.length() >= 16 ) {
            return password;
        }
        // duplicate pwd until length of 16 chars is reached
        while ( password.length() < 16 ) {
            password += password;
        }
        password = password.substring(0, 16);
        // return modded pwd
        return password;
    }

    // encrypt input text with given pwd and save it as app data file
    // credits: https://stackoverflow.com/questions/22863889/encrypt-decrypt-file-and-incorrect-data
    void encryptStorageFile(String clearTextString, String password) throws
            IOException,
            NoSuchAlgorithmException,
            NoSuchPaddingException,
            InvalidKeyException {
        // read the cleartext
        InputStream tis = new ByteArrayInputStream(clearTextString.getBytes(StandardCharsets.UTF_8));
        // stream to write the encrypted text to file
        String storagePath = this.getExternalFilesDir(null).getAbsolutePath();
        String appName = getApplicationInfo().loadLabel(getPackageManager()).toString();
        String storageFileName = storagePath + "/" + appName;
        FileOutputStream fos = new FileOutputStream(storageFileName);
        // PWD length is 16 byte
        SecretKeySpec sks = new SecretKeySpec(password.getBytes(), "AES");
        // create cipher
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, sks);
        // cipher output stream
        CipherOutputStream cos = new CipherOutputStream(fos, cipher);
        // write bytes
        int b;
        byte[] d = new byte[8];
        while( (b = tis.read(d)) != -1 ) {
            cos.write(d, 0, b);
        }
        // flush and close streams
        cos.flush();
        cos.close();
        tis.close();
        // there is not editor dirty flag after saving data
        setTextEditorChanged(false);
        // update data storage sile size
        File file = new File(storageFileName);
        storageFileSize = file.length();
    }

    // open app data file, decrypt it with given pwd and return it as string
    String decryptStorageFile(String password) throws
            IOException,
            NoSuchAlgorithmException,
            NoSuchPaddingException,
            InvalidKeyException {
        String clearTextString = "";

        SecretKeySpec sks = new SecretKeySpec(password.getBytes(), "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, sks);

        String storagePath = this.getExternalFilesDir(null).getAbsolutePath();
        String appName = getApplicationInfo().loadLabel(getPackageManager()).toString();
        String storageFileName = storagePath + "/" + appName;

        // applies to first app usage with no data file
        File file = new File(storageFileName);
        if ( !file.exists() || file.length() == 0 ) {
            return clearTextString;
        }

        FileInputStream fis = new FileInputStream(storageFileName);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CipherInputStream cis = new CipherInputStream(fis, cipher);
        int b;
        byte[] d = new byte[8];
        while ( (b = cis.read(d)) != -1 ) {
            bos.write(d, 0, b);
        }
        bos.flush();
        clearTextString = bos.toString("UTF-8");
        bos.close();
        cis.close();
        fis.close();

        return clearTextString;
    }

    // simple AlertBuilder ok box
    void okBox(String title, String message) {
        AlertDialog.Builder builder = null;
        builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage("\n" + message);
        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        AlertDialog alert = builder.create();
        alert.show();
        alert.setCanceledOnTouchOutside(false);
    }

    // ask whether to 'show again' in an alert box
    void dontShowAgain(String title, CharSequence[] items, String prefKeyString) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        if ( sharedPref.getBoolean(prefKeyString, false) ) {
            return;
        }
        AlertDialog.Builder builder = null;
        builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle(title);
        final int[] selected = {1};
        builder.setSingleChoiceItems(items, selected[0], new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                selected[0] = which;
            }
        });
        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                SharedPreferences.Editor spe = sharedPref.edit();
                spe.putBoolean(prefKeyString, selected[0] == 0 ? true : false);
                spe.apply();
            }
        });
        AlertDialog alert = builder.create();
        alert.show();
        alert.setCanceledOnTouchOutside(false);
    }

    // messagebox at first data usage
    void firstDataUsage() {
        String storagePath = MainActivity.this.getExternalFilesDir(null).getAbsolutePath();
        String appName = getApplicationInfo().loadLabel(getPackageManager()).toString();
        String storageFileName = storagePath + "/" + appName;
        File file = new File(storageFileName);
        storageFileSize = file.length();
        if ( !file.exists() || storageFileSize == 0 ) {
            // give a note about the magic password
            okBox("GrzNote first data usage",
                    "Create a password.\nLength: 4 ... 16 characters.\n\n" +
                            "You need to remember it, anytime you use this app.\n\n" +
                            "The password is not stored anywhere.");
        }
    }

}