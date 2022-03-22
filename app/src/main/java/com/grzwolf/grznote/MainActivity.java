package com.grzwolf.grznote;
 
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
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

    // status vars
    boolean  textEditorChanged = false;
    boolean  storageFileIsOpen = false;
    long     storageFileSize = 0;

    // app lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        this.setTitle("GrzNote " + BuildConfig.VERSION_NAME);

        // active controls
        exitButton = (Button) findViewById(R.id.exitButton);
        saveButton = (Button) findViewById(R.id.saveButton);
        openButton = (Button) findViewById(R.id.openButton);
        textEditor = (EditText) findViewById(R.id.text);

        // text editor change listener
        textEditor.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                textEditorChanged = true;
            }
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        // initially start with file open dlg
        openButton.performClick();

        // friendly reminder
        dontShowAgain("Your password is not stored anywhere.",
                new CharSequence[] {"Don't show again", "Show again"},
                "DontShowAgain");
    }

    // password and password error handling
    String difficultPassword = "";
    int errorCounterPassword = 0;
    public int getErrorCounterPassword() {
        return errorCounterPassword;
    }
    public void setErrorCounterPassword(int errorCounterPassword) {
        this.errorCounterPassword = errorCounterPassword;
        // > 5 pwd errors
        if ( this.errorCounterPassword > 5 ) {
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
                            resetRestartApp();
                        }
                    });
                    builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
                    builder.show();
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
            adYesNoCont.show();
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

        // save text from editor to file
        if ( v.getId() == R.id.saveButton ) {

            // if editor is not enabled, there is nothing to save
            if ( !textEditor.isEnabled() ) {
                return;
            }

            // avoid accidental override of a not empty data file
            if ( !storageFileIsOpen && storageFileSize > 0 ) {
                okBox("Error data file",
                        "The data file was not yet opened and contains data.\n\nOpen the file first.");
                return;
            }

            // for the sake of mind
            AlertDialog.Builder adYesNo = new AlertDialog.Builder(this);
            adYesNo.setMessage("The current text will be saved to the data file.\n\nContinue?");
            adYesNo.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
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
                                // apply pwd
                                try {
                                    // get clear text
                                    String clearTextOut = textEditor.getText().toString();
                                    // save encrypted text to file
                                    encryptStorageFile(clearTextOut, difficultPassword);
                                    // immediately re read encrypted data from file, decrypt it and show
                                    openButton.performClick();
                                    // if everything went well, the pwd error counter is reset
                                    setErrorCounterPassword(0);
                                } catch (IOException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException exc) {
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
                        ad.show();
                        // repeat welcome screen on top of the pwd dlg at first usage
                        firstDataUsage();
                    } else {
                        // if pwd is somehow ok, use it
                        try {
                            String clearTextString = textEditor.getText().toString();
                            encryptStorageFile(clearTextString, difficultPassword);
                            openButton.performClick();
                        } catch (IOException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException exc) {
                            difficultPassword = "";
                            okBox("Error", exc.getMessage());
                        }
                    }
                }
            });
            adYesNo.setNegativeButton("No", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });
            adYesNo.show();
        }

        // decrypted file open handling
        if( v.getId() == R.id.openButton ) {
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
                            textEditorChanged = false;
                            // data storage file is now open
                            storageFileIsOpen = true;
                            // enable text edit, when file data are shown
                            textEditor.setEnabled(true);
                        } catch (IOException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException exc) {
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
                ad.show();
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
                                textEditorChanged = false;
                                storageFileIsOpen = true;
                                textEditor.setEnabled(true);
                            } catch (IOException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException exc) {
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
                    adYesNo.show();
                } else {
                    // there is a pwd and the text editor has no changes: simply open the decrypted file and show it
                    try {
                        String clearText = decryptStorageFile(difficultPassword);
                        textEditor.setText(clearText);
                        textEditorChanged = false;
                        storageFileIsOpen = true;
                        textEditor.setEnabled(true);
                    } catch (IOException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException exc) {
                        difficultPassword = "";
                        okBox("Error Password", "Something went wrong.");
                    }
                }
            }
        }

        // close app - ask whether to save or not
        if ( v.getId() == R.id.exitButton ) {
            // for the sake of mind
            AlertDialog.Builder adYesNo = new AlertDialog.Builder(this);
            String text = textEditorChanged ? "This will fully close the app. Unsaved data will be lost." : "This will fully close the app, even from recent apps.";
            adYesNo.setMessage(text + "\n\nContinue?");
            adYesNo.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finishAndRemoveTask();
                }
            });
            adYesNo.setNegativeButton("No", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });
            adYesNo.show();
        }
    }

    // extend password to 16 chars
    String pwdModder(String difficultPassword) {
        // if pwd is empty, return a definitely failing pwd instead
        if ( difficultPassword.length() == 0 ) {
            return "0";
        }
        // pwd length is too short OR too long OR length is ok
        if ( difficultPassword.length() < 4 || difficultPassword.length() >= 16 ) {
            return difficultPassword;
        }
        // duplicate pwd until length of 16 chars is reached
        while ( difficultPassword.length() < 16 ) {
            difficultPassword += difficultPassword;
        }
        difficultPassword = difficultPassword.substring(0, 16);
        // return modded pwd
        return difficultPassword;
    }

    // encrypt input text with given pwd and save it as app data file
    // credits: https://stackoverflow.com/questions/22863889/encrypt-decrypt-file-and-incorrect-data
    void encryptStorageFile(String clearTextString, String MyDifficultPassw) throws IOException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException {
        // read the cleartext
        InputStream tis = new ByteArrayInputStream(clearTextString.getBytes(StandardCharsets.UTF_8));
        // stream to write the encrypted text to file
        String storagePath = this.getExternalFilesDir(null).getAbsolutePath();
        String appName = getApplicationInfo().loadLabel(getPackageManager()).toString();
        String storageFileName = storagePath + "/" + appName;
        FileOutputStream fos = new FileOutputStream(storageFileName);
        // PWD length is 16 byte
        SecretKeySpec sks = new SecretKeySpec(MyDifficultPassw.getBytes(), "AES");
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
        textEditorChanged = false;
        // update data storage sile size
        File file = new File(storageFileName);
        storageFileSize = file.length();
    }

    // open app data file, decrypt it with given pwd and return it as string
    String decryptStorageFile(String MyDifficultPassw) throws IOException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException {
        String clearTextString = "";

        SecretKeySpec sks = new SecretKeySpec(MyDifficultPassw.getBytes(), "AES");
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
        builder.show();
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
        builder.create().show();
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
                    "Create a password.\nLength: 4 ... 16 characters.\n\nYou need to remember it, anytime you use this app.\n\nThe password is not stored anywhere.");
        }
    }

}