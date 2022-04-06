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
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

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
    EditText    textEditor;
    ImageButton saveButton;
    Button      exitButton,
                openButton;
    ScrollView  textEditorScroller = null;// scroll text to a certain position
    AlertDialog alertSaveChanges = null;  // specific dlg to dismiss, if going to pause

    // status vars
    boolean  storageFileIsOpen = false;   // prepare for accidental file override
    long     storageFileSize = 0;         // prepare for accidental file override
    boolean  appIsHardClosing = false;    // reset pref "LatestUserActivityTime"
    boolean  wentThruOnCreate = false;    // allow checkPwdTimeout in onResume if no fresh start
    String   textEditorPauseContent = ""; // if textEditorDirty, store the content of textEditor in onPause

    boolean  textEditorDirty = false;     // textEditor dirty flag
    boolean getTextEditorDirty() {        // textEditor dirty flag getter & setter: indicates editor dirty status as 'save button' text color
        return textEditorDirty;
    }
    void setTextEditorDirty(boolean isDirty) {
        textEditorDirty = isDirty;
        if ( isDirty ) {
            // alert color save icon
            saveButton.setImageResource(android.R.drawable.ic_menu_save);
            saveButton.setBackgroundColor(Color.YELLOW);
            saveButton.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_ATOP);
        } else {
            // green color lock icon (read only mode)
            saveButton.setBackgroundColor(Color.BLACK);
            if ( storageFileIsOpen ) {
                saveButton.setImageResource(android.R.drawable.ic_lock_lock);
                saveButton.setColorFilter(Color.GREEN, PorterDuff.Mode.SRC_ATOP);
            }
        }
    }

    int errorCounterPassword = 0;         // 'password error' counter
    int getErrorCounterPassword() {       // 'password error' counter getter & setter: to organize its handling
        return errorCounterPassword;
    }
    void setErrorCounterPassword(int counter) {
        errorCounterPassword = counter;
        // > 5 pwd errors
        if ( errorCounterPassword > 5 ) {
            dlgAppResetToDefault();
        }
    }

    String difficultPassword = "";        // password: it is not stored anywhere, it only lives during the app session

    String searchInputString = "";        // most recent text search input string

    Timer pwdTimer = null;                // pwd TIMEOUT timer: 2min = 120s = 120.000ms
    static final long PWD_TIMEOUT = 120000;

    //
    // app lifecycle methods
    //
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        this.setTitle("GrzNote " + BuildConfig.VERSION_NAME);

        // a flag is needed to distinguish, whether the app is really started OR just resumed
        wentThruOnCreate = true;

        // init active controls
        textEditor = (EditText) findViewById(R.id.text);
        saveButton = (ImageButton) findViewById(R.id.saveButton);
        exitButton = (Button) findViewById(R.id.exitButton);
        openButton = (Button) findViewById(R.id.openButton);

        // scroll text to a certain position
        textEditorScroller = (ScrollView) findViewById(R.id.scrollerMain);

        // text editor change listener
        textEditor.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                // simple text editor change/dirty flag
                setTextEditorDirty(true);
                // user interaction happened
                recordUserActivityTime();
            }
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        // long press event handler shall start text search in textEditor
        textEditor.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                // only allow text search in locked mode (in edit mode, it would interfere with context menu)
                if ( !textEditor.isFocusable() && textEditor.getText().length() > 0 ) {
                    dlgHighlightSearchText();
                    return true;
                }
                return false;
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
        dlgDontShowAgain("Your password is not stored anywhere.",
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
            // if textEditor is dirty, save it temporarily (will be later handled in onResume / checkPwdTimeout, if pwd is not expired)
            textEditorPauseContent = "";
            if ( getTextEditorDirty() ) {
                textEditorPauseContent = textEditor.getText().toString();
            }
            // make editor text disappear from view in recent apps
            textEditor.setText("");
            setTextEditorDirty(false);
            // simple pause shall record the time when it happens to allow a password expire TIMEOUT
            recordUserActivityTime();
        }
        super.onPause();
    }

    //
    // main user action (other than editing text, text search) takes place here
    //
    public void buttonAction(View v) {

        // user interaction happened
        recordUserActivityTime();

        // storage file save handling AND edit mode toggle
        if ( v.getId() == R.id.saveButton ) {
            // if editor is not yet in edit mode, toggle: edit mode <--> save text from textEditor to file
            if ( !textEditor.isFocusable() ) {
                // only if file is open, toggle button text from "Edit" to "Save" and enable edit mode
                if ( storageFileIsOpen ) {
                    dlgTextEditorSetEditmode();
                }
                return;
            }
            // avoid accidental override of a not empty data file
            if ( !storageFileIsOpen && storageFileSize > 0 ) {
                okBox("Error data file",
                        "The data file was not yet opened and contains data.\n\nOpen the file first.");
                return;
            }
            // if nothing to save, just re open file in locked mode (= default open file)
            if ( !getTextEditorDirty() ) {
                openButton.performClick();
                return;
            }
            // top level dialog to save textEditor data to file
            dlgSaveData();
        }

        // storage file open handling
        if( v.getId() == R.id.openButton ) {
            // if there is no pwd, ask for pwd, open file, decrypt it, show it as clear text in editor
            if ( difficultPassword.length() == 0 ) {
                dlgPasswordAndOpen();
                // repeat welcome screen on the top of the pwd dlg at first usage
                firstDataUsage();
            } else {
                // there is a pwd and the text editor was previously changed: ask about potential data loss from editor to file
                if ( getTextEditorDirty() ) {
                    dlgUnsavedDataLoss();
                } else {
                    // there is a pwd and the text editor has no changes: simply open the decrypted file and show it
                    readTextFromFileIntoEditor();
                }
            }
        }

        // close app handling - in case of dirty textEditor, ask whether to save data before exit
        if ( v.getId() == R.id.exitButton ) {
            // for the sake of mind
            dlgExitApp();
        }
    }

    // after 5x password error, ask whether to reset app to defaults (aka like fresh install)
    void dlgAppResetToDefault() {
        recordUserActivityTime();
        // now there are three choices ...
        AlertDialog.Builder adYesNoCont = new AlertDialog.Builder(this);
        adYesNoCont.setMessage("Error password > 5\n\nStart from scratch with a new data file and delete the old data file?");
        // ... 1) option to delete the data storage file encrypted with an unknown password and restart app
        adYesNoCont.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // 'are you sure'
                dlgYouSureAppResetToDefault();
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
    // 'are you sure' before app reset to default
    void dlgYouSureAppResetToDefault() {
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

    // ask user for search text, highlight it in textEditor and scroll to first match
    void dlgHighlightSearchText() {
        recordUserActivityTime();
        final EditText searchInput = new EditText(MainActivity.this);
        searchInput.setText(searchInputString);
        // search text input dialog
        new AlertDialog.Builder(MainActivity.this)
                .setTitle("Search")
                .setView(searchInput)
                .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        searchInputString = searchInput.getText().toString().toLowerCase();
                        // set highlighted text: https://stackoverflow.com/questions/22890075/android-edittext-highlight-multiple-words-in-the-text
                        String tvt = textEditor.getText().toString().toLowerCase();
                        int ofe = tvt.indexOf(searchInputString, 0);
                        Spannable WordtoSpan = new SpannableString(textEditor.getText());
                        int firstMatchPosition = -1;
                        for ( int ofs = 0; ofs < tvt.length() && ofe != -1; ofs = ofe + 1 ) {
                            ofe = tvt.indexOf(searchInputString, ofs);
                            if ( ofe == -1 ) {
                                break;
                            } else {
                                if ( firstMatchPosition == -1 ) {
                                    firstMatchPosition = ofe;
                                }
                                WordtoSpan.setSpan(new BackgroundColorSpan(Color.GREEN), ofe, ofe + searchInputString.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                textEditor.setText(WordtoSpan, TextView.BufferType.SPANNABLE);
                                setTextEditorDirty(false);
                            }
                        }
                        // let textEditor scroll to first matching position
                        if ( firstMatchPosition != -1 ) {
                            textEditor.setSelection(firstMatchPosition);
                            Layout layout = textEditor.getLayout();
                            textEditorScroller.scrollTo(0, layout.getLineTop(layout.getLineForOffset(firstMatchPosition)));
                        } else {
                            okBox("Text search","'" + searchInputString + "' not found");
                        }
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                    }
                }).show();
    }

    //
    // pwd expiration timer
    //
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
    void checkPwdTimeout(boolean openFile) {
        // in case, there is now valid pwd, simply return
        if ( difficultPassword.length() == 0 ) {
            return;
        }
        // get time of the last user activity, credits: https://stackoverflow.com/questions/576600/lock-android-app-after-a-certain-amount-of-idle-time
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        long pausedStart = preferences.getLong("LatestUserActivityTime", -1);
        if ( pausedStart == -1 ) {
            return;
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
                    // avoid confusion after onPause --> onResume with a previously open 'save changes dialog'
                    if ( alertSaveChanges != null && alertSaveChanges.isShowing() ) {
                        alertSaveChanges.cancel();
                    }
                    textEditor.setText(textEditorPauseContent);
                    textEditorPauseContent = "";
                    storageFileIsOpen = true;
                    setTextEditorEditmode(true);
                    setTextEditorDirty(true);
                } else {
                    // otherwise simply re open storage file
                    openButton.performClick();
                }
            }
        }
    }
    // reset pwd timer, pwd, text editor content, file status + give message
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
                    // in case, the save data dlg was open
                    if ( alertSaveChanges != null && alertSaveChanges.isShowing() ) {
                        alertSaveChanges.cancel();
                    }
                    // reset file status
                    storageFileIsOpen = false;
                    storageFileSize = 0;
                    // reset text editor
                    textEditor.setText("");
                    setTextEditorDirty(false);
                    setTextEditorEditmode(false);
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
    // pwd TIMEOUT helper: record time of the most recent user activity - allows checkPwdTimeout(..)
    public void recordUserActivityTime() {
        long time = System.currentTimeMillis();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = preferences.edit();
        edit.putLong("LatestUserActivityTime", time);
        edit.apply();
    }

    //
    // save data: top level dialog to save textEditor data to file
    //
    void dlgSaveData() {
        recordUserActivityTime();
        AlertDialog.Builder adYesNoCancel = new AlertDialog.Builder(this);
        adYesNoCancel.setMessage("The current text will be saved.\n\n" +
                "Yes       = save changes\n" +
                "Cancel = continue edit\n" +
                "No         = discard changes");
        adYesNoCancel.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // text from textEditor
                String clearTextString = textEditor.getText().toString();
                // empty text check
                if ( clearTextString.length() == 0 ) {
                    dlgWriteEmptyTextToFile();
                } else {
                    writeTextToFile(clearTextString);
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
    // ask whether to discard unsaved data
    void dlgUnsavedDataLoss() {
        recordUserActivityTime();
        AlertDialog.Builder adYesNo = new AlertDialog.Builder(this);
        adYesNo.setMessage("Unsaved changes will be lost. Continue?");
        adYesNo.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                readTextFromFileIntoEditor();
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
    // ask whether to write an empty text into the storage file
    void dlgWriteEmptyTextToFile() {
        recordUserActivityTime();
        AlertDialog.Builder adYesNo = new AlertDialog.Builder(MainActivity.this);
        adYesNo.setMessage("Current text is empty.\n\nYou sure to continue anyway?");
        adYesNo.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                writeTextToFile("");
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

    //
    // open data: ask for password, open storage file and show in textEditor
    //
    void dlgPasswordAndOpen() {
        recordUserActivityTime();
        final EditText pwdEditor = new EditText(MainActivity.this);
        AlertDialog.Builder ad = new AlertDialog.Builder(MainActivity.this);
        ad.setView(pwdEditor);
        ad.setMessage("Type password");
        ad.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                difficultPassword = pwdEditor.getText().toString();
                difficultPassword = pwdModder(difficultPassword);
                readTextFromFileIntoEditor();
            }
        });
        ad.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                difficultPassword = "";
                setTextEditorEditmode(false);
                dialog.cancel();
            }
        });
        AlertDialog alert = ad.create();
        alert.show();
        alert.setCanceledOnTouchOutside(false);
    }

    //
    // app exit: ask whether to exit the app
    //
    void dlgExitApp() {
        recordUserActivityTime();
        AlertDialog.Builder adYesNo = new AlertDialog.Builder(this);
        String text = getTextEditorDirty() ? "This will fully close the app." +
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

    // ask whether to switch to edit mode of textEditor
    void dlgTextEditorSetEditmode() {
        recordUserActivityTime();
        AlertDialog.Builder adYesNo = new AlertDialog.Builder(this);
        adYesNo.setMessage("This will enable the edit mode.\n\nContinue?");
        adYesNo.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                setTextEditorEditmode(true);
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
    // method to toggle 'edit mode' vs. 'read mode' for textEditor
    void setTextEditorEditmode(boolean enable) {
        if ( enable ) {
            saveButton.setBackgroundColor(Color.LTGRAY);
            saveButton.setImageResource(android.R.drawable.ic_menu_edit);
            saveButton.setColorFilter(Color.parseColor("#ff4444"), PorterDuff.Mode.SRC_ATOP);
            textEditor.setFocusableInTouchMode(true);
            textEditor.setKeyListener(new EditText(getApplicationContext()).getKeyListener());
            textEditor.setFocusable(true);
            textEditor.setCursorVisible(true);
            textEditor.requestFocus();
            textEditor.getBackground().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP);
        } else {
            saveButton.setBackgroundColor(Color.BLACK);
            if ( storageFileIsOpen ) {
                saveButton.setImageResource(android.R.drawable.ic_lock_lock);
                saveButton.setColorFilter(Color.GREEN, PorterDuff.Mode.SRC_ATOP);
            }
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

    // write text into the storage file, re read saved data into textEditor
    void writeTextToFile(String clearTextString) {
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
    // encrypt input text with a given pwd and save it as app data file
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
        SecretKeySpec sks = new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "AES");
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
        // clear editor dirty flag after saving data
        setTextEditorDirty(false);
        // update data storage sile size
        File file = new File(storageFileName);
        storageFileSize = file.length();
    }

    // read text from storage file into textEditor
    void readTextFromFileIntoEditor() {
        try {
            // read from encrypted file, decrypt data and show them
            String clearText = decryptStorageFile(difficultPassword);
            // data storage file is now open
            storageFileIsOpen = true;
            // if app flow goes here, there was at least a pwd success
            setErrorCounterPassword(0);
            // set clear text to textEditor
            textEditor.setText(clearText);
            // clear editor dirty flag after setting the data to editor from file
            setTextEditorDirty(false);
            // disable edit mode after file open & toggle 'Save/Edit' button text to "Edit"
            setTextEditorEditmode(false);
            // start pwd timeout timer
            startPwdTimer();
        } catch (IOException |
                NoSuchAlgorithmException |
                NoSuchPaddingException |
                InvalidKeyException exc) {
            setErrorCounterPassword(getErrorCounterPassword() + 1);
            difficultPassword = "";
            setTextEditorEditmode(false);
            okBox("Error Password", "Something went wrong.");
        }
    }
    // open encrypted app data file, decrypt it with a given pwd and return decrypted data as string
    String decryptStorageFile(String password) throws
            IOException,
            NoSuchAlgorithmException,
            NoSuchPaddingException,
            InvalidKeyException {
        String clearTextString = "";

        SecretKeySpec sks = new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "AES");
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
    void dlgDontShowAgain(String title, CharSequence[] items, String prefKeyString) {
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
                            "If you forget the password, your data are lost.\n\n" +
                            "The password is not stored anywhere.");
        }
    }

}