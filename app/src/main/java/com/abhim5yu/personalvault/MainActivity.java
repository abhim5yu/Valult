package com.abhim5yu.personalvault;

import android.os.Bundle;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.View;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.text.Editable;
import android.text.TextWatcher;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {
    private final String PREF = "vault_store.bin";
    private LinearLayout root, content, list;
    private EditText search;
    private JSONArray items = new JSONArray();
    private boolean dark = false, unlocked = false, relockOnResume = false;
    private String pendingImage = "";
    private int bg, card, fg, muted, accent;
    private ActivityResultLauncher<String> imagePicker;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(246,247,239));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(246,247,239));
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                if (bitmap == null) { toast("Could not read image"); return; }
                int w=bitmap.getWidth(), h=bitmap.getHeight();
                float scale=Math.min(1f, 1200f/Math.max(w,h));
                Bitmap small=Bitmap.createScaledBitmap(bitmap, Math.max(1,(int)(w*scale)), Math.max(1,(int)(h*scale)), true);
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                small.compress(Bitmap.CompressFormat.JPEG, 78, out);
                pendingImage=Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                toast("Image attached");
                showAddDialog();
            } catch(Exception e) { toast("Image could not be loaded"); }
        });
        applyColors();
        showLock();
    }

    private void applyColors() {
        bg = android.graphics.Color.parseColor(dark ? "#10110F" : "#F5F6EF");
        card = android.graphics.Color.parseColor(dark ? "#20211E" : "#FFFFFF");
        fg = android.graphics.Color.parseColor(dark ? "#F4F4EF" : "#171814");
        muted = android.graphics.Color.parseColor(dark ? "#B4B6AA" : "#77796F");
        accent = android.graphics.Color.parseColor("#B9D96A");
    }

    private void showLock() {
        applyColors();
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER); root.setPadding(28,32,28,32); root.setBackgroundColor(bg);
        TextView mark = label("◈", 48, accent); root.addView(mark);
        TextView title = label("Personal Vault", 30, fg); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub = label("Your essentials. Only yours.", 15, muted); sub.setGravity(Gravity.CENTER);
        root.addView(sub, params(-1,58));
        Button unlock = button("Unlock with fingerprint"); root.addView(unlock, params(-1,58));
        TextView note=label("Stored on this device • No account • No cloud",12,muted); note.setGravity(Gravity.CENTER); root.addView(note);
        setContentView(root);
        unlock.setOnClickListener(v -> authenticate());
        authenticate();
    }

    private void authenticate() {
        BiometricManager bm=BiometricManager.from(this);
        int can=bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        if(can!=BiometricManager.BIOMETRIC_SUCCESS) {
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Screen lock required")
              .setMessage("Set up a fingerprint or secure screen lock in Android Settings, then reopen Personal Vault.")
              .setPositiveButton("OK",(d,w)->{}).show(); return;
        }
        Executor executor=ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt=new BiometricPrompt(this,executor,new BiometricPrompt.AuthenticationCallback(){
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){unlocked=true; loadData(); showHome();}
            @Override public void onAuthenticationFailed(){toast("Not recognized. Try again.");}
        });
        BiometricPrompt.PromptInfo info=new BiometricPrompt.PromptInfo.Builder()
          .setTitle("Unlock Personal Vault").setSubtitle("Authenticate to access your private information")
          .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL).build();
        prompt.authenticate(info);
    }

    private SecretKey getKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(ks.containsAlias("personal_vault_key")) return ((KeyStore.SecretKeyEntry)ks.getEntry("personal_vault_key",null)).getSecretKey();
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder("personal_vault_key",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
        return kg.generateKey();
    }
    private File dataFile(){return new File(getFilesDir(),PREF);}
    private void loadData(){
        try {
            File f=dataFile(); if(!f.exists()){items=new JSONArray();return;}
            DataInputStream in=new DataInputStream(new FileInputStream(f));
            int n=in.readInt(); byte[] iv=new byte[n]; in.readFully(iv);
            int len=in.readInt(); byte[] enc=new byte[len]; in.readFully(enc); in.close();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,getKey(),new GCMParameterSpec(128,iv));
            items=new JSONArray(new String(c.doFinal(enc),StandardCharsets.UTF_8));
        } catch(Exception e){items=new JSONArray(); toast("Vault data could not be opened. Existing file was left untouched.");}
    }
    private void saveData(){
        try {
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,getKey());
            byte[] enc=c.doFinal(items.toString().getBytes(StandardCharsets.UTF_8));
            DataOutputStream out=new DataOutputStream(new FileOutputStream(dataFile()));
            byte[] iv=c.getIV(); out.writeInt(iv.length); out.write(iv); out.writeInt(enc.length); out.write(enc); out.close();
        } catch(Exception e){toast("Save failed. Check device storage.");}
    }

    private void showHome(){
        applyColors();
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(bg); root.setPadding(20,18,20,8);
        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles=new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(label("PERSONAL VAULT",11,muted));
        titles.addView(label("Your essentials.",29,fg));
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        Button theme=button(dark?"☀":"☾"); top.addView(theme,params(52,48)); theme.setOnClickListener(v->{dark=!dark;showHome();});
        Button lock=button("⌑"); top.addView(lock,params(52,48)); lock.setOnClickListener(v->{unlocked=false;showLock();});
        root.addView(top);
        TextView tagline=label("Private by design. Offline by default.",13,muted); root.addView(tagline,params(-1,34));
        search=new EditText(this); search.setSingleLine(true); search.setTextSize(15); search.setHint("Search names, notes, tags, text…");
        search.setPadding(18,4,18,4); search.setBackground(round(card,18)); search.setTextColor(fg); search.setHintTextColor(muted);
        root.addView(search,params(-1,54));
        LinearLayout chips=new LinearLayout(this); chips.setPadding(0,12,0,10);
        String[] cats={"All","Identity","Banking","Personal","Other"};
        for(String c:cats){TextView chip=label(c,12,fg);chip.setGravity(Gravity.CENTER);chip.setPadding(12,0,12,0);chip.setBackground(round(c.equals("All")?accent:card,20));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,38);cp.setMargins(0,0,7,0);chips.addView(chip,cp);chip.setOnClickListener(v->{search.setText(c.equals("All")?"":c);});}
        root.addView(chips);
        ScrollView sc=new ScrollView(this); content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        TextView heading=label("SAVED ITEMS",12,muted); content.addView(heading,params(-1,35));
        list=content; sc.addView(content); root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        Button add=button("+   Add information"); add.setTextSize(16); root.addView(add,params(-1,56)); add.setOnClickListener(v->{pendingImage="";showAddDialog();});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){renderItems(s.toString());}public void afterTextChanged(Editable e){}});
        setContentView(root); renderItems("");
    }

    private void renderItems(String query){
        if(list==null)return;
        while(list.getChildCount()>1)list.removeViewAt(1);
        String q=query.toLowerCase().trim(); int count=0;
        for(int i=items.length()-1;i>=0;i--){
            JSONObject item=items.optJSONObject(i); if(item==null)continue;
            String hay=(item.optString("name")+" "+item.optString("category")+" "+item.optString("tags")+" "+item.optString("body")).toLowerCase();
            if(!hay.contains(q))continue; count++;
            LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(16,14,16,14);box.setBackground(round(card,22));
            LinearLayout.LayoutParams bp=params(-1,-2);bp.setMargins(0,0,0,10);list.addView(box,bp);
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView symbol=label(item.optString("image","").isEmpty()?"▤":"▧",22,fg);symbol.setGravity(Gravity.CENTER);symbol.setBackground(round(accent,15));row.addView(symbol,params(46,46));
            LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(12,0,0,0);
            names.addView(label(item.optString("name","Untitled"),17,fg));names.addView(label(item.optString("category","Other")+"  ·  "+item.optString("date",""),12,muted));
            row.addView(names,new LinearLayout.LayoutParams(0,-2,1));row.addView(label("›",26,muted));box.addView(row);
            if(!item.optString("body","").isEmpty()){TextView preview=label(item.optString("body"),13,muted);preview.setMaxLines(2);preview.setPadding(58,8,0,0);box.addView(preview);}
            final int idx=i;box.setOnClickListener(v->showDetails(idx));
        }
        if(count==0){TextView empty=label(q.isEmpty()?"Nothing saved yet":"No matching information",16,muted);empty.setGravity(Gravity.CENTER);empty.setPadding(10,50,10,50);list.addView(empty);}
    }

    private void showAddDialog(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(20,4,20,0);
        EditText name=field("Name (e.g. Aadhaar card)");EditText cat=field("Category (Identity, Banking, Personal…)");EditText tags=field("Tags (comma separated)");EditText body=field("Text, number, notes or details");body.setMinLines(3);body.setGravity(Gravity.TOP);
        form.addView(name);form.addView(cat);form.addView(tags);form.addView(body);
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Add to your vault").setView(form)
          .setNeutralButton("＋ Image",(d,w)->{pendingImage="";imagePicker.launch("image/*");})
          .setNegativeButton("Cancel",(d,w)->{})
          .setPositiveButton("Save",(d,w)->{
              if(name.getText().toString().trim().isEmpty()){toast("Add a name first");return;}
              JSONObject obj=new JSONObject();
              try{obj.put("name",name.getText().toString().trim());obj.put("category",cat.getText().toString().trim().isEmpty()?"Other":cat.getText().toString().trim());obj.put("tags",tags.getText().toString());obj.put("body",body.getText().toString());obj.put("image",pendingImage);obj.put("date",new java.text.SimpleDateFormat("dd MMM yyyy",java.util.Locale.getDefault()).format(new java.util.Date()));items.put(obj);saveData();pendingImage="";showHome();}
              catch(Exception e){toast("Could not save item");}
          }).show();
    }
    private void showDetails(int idx){
        JSONObject o=items.optJSONObject(idx);if(o==null)return;
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(20,4,20,4);
        if(!o.optString("image","").isEmpty())try{byte[] b=Base64.decode(o.optString("image"),Base64.DEFAULT);ImageView im=new ImageView(this);im.setImageBitmap(BitmapFactory.decodeByteArray(b,0,b.length));im.setAdjustViewBounds(true);box.addView(im,params(-1,220));}catch(Exception ignored){}
        TextView body=label(o.optString("body","(No text saved)"),15,fg);body.setTextIsSelectable(true);box.addView(body);
        TextView tags=label("Tags: "+o.optString("tags","—"),12,muted);box.addView(tags);
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(o.optString("name")).setView(box)
          .setNegativeButton("Close",(d,w)->{})
          .setNeutralButton("Delete",(d,w)->new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Delete this item?").setMessage("This cannot be undone.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(dd,ww)->{items.remove(idx);saveData();showHome();}).show())
          .show();
    }
    private EditText field(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(15);e.setPadding(12,8,12,8);return e;}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(android.graphics.Color.parseColor("#171814"));b.setBackground(round(accent,22));return b;}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private android.graphics.drawable.GradientDrawable round(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private LinearLayout.LayoutParams params(int w,int h){return new LinearLayout.LayoutParams(w<0?w:w,h<0?h:h);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override public void onBackPressed(){if(unlocked){unlocked=false;showLock();}else super.onBackPressed();}
    @Override protected void onStop(){super.onStop();if(unlocked){unlocked=false;relockOnResume=true;}}
    @Override protected void onResume(){super.onResume();if(relockOnResume){relockOnResume=false;showLock();}}
}