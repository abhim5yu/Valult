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
import android.app.Dialog;
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
    private boolean dark = false, unlocked = false, relockOnResume = false, pickingImage = false;
    private String pendingImage = "";
    private int bg, card, fg, muted, accent;
    private ActivityResultLauncher<String> imagePicker;
    private EditText draftName, draftCat, draftTags, draftBody;
    private TextView imageStatus;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(246,247,239));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(246,247,239));
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            pickingImage = false;
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
                if(imageStatus!=null) imageStatus.setText("Image attached  ✓");
                toast("Image attached");
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
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().getDecorView().setSystemUiVisibility(dark ? 0 :
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(20,16,20,14);

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView brand=label("PERSONAL VAULT",11,muted);
        brand.setLetterSpacing(.08f);
        titles.addView(brand);
        TextView heading=label("Your essentials",27,fg);
        heading.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        titles.addView(heading);
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));

        Button theme=smallIconButton(dark?"☀":"☾");
        top.addView(theme,params(44,44));
        theme.setOnClickListener(v->{dark=!dark;showHome();});
        Button lock=smallIconButton("⌑");
        LinearLayout.LayoutParams lp=params(44,44);lp.leftMargin=8;top.addView(lock,lp);
        lock.setOnClickListener(v->{unlocked=false;showLock();});
        root.addView(top);

        TextView tagline=label("Private by design. Offline by default.",13,muted);
        LinearLayout.LayoutParams tagp=params(-1,30);tagp.topMargin=2;root.addView(tagline,tagp);

        search=new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setHint("⌕   Search everything…");
        search.setPadding(16,0,16,0);
        search.setBackground(round(card,16));
        search.setTextColor(fg);
        search.setHintTextColor(muted);
        search.setElevation(0);
        root.addView(search,params(-1,50));

        HorizontalScrollView horizontal=new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout chips=new LinearLayout(this);
        chips.setPadding(0,12,0,14);
        String[] cats={"All","Identity","Banking","Personal","Other"};
        for(String category:cats){
            TextView chip=label(category,13,fg);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(15,0,15,0);
            chip.setBackground(round(category.equals("All")?accent:card,18));
            LinearLayout.LayoutParams cp=params(-2,34);
            cp.setMargins(0,0,8,0);
            chips.addView(chip,cp);
            chip.setOnClickListener(v->{
                search.setText(category.equals("All")?"":category);
                search.setSelection(search.getText().length());
            });
        }
        horizontal.addView(chips);
        root.addView(horizontal);

        LinearLayout section=new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        TextView saved=label("SAVED ITEMS",11,muted);
        saved.setLetterSpacing(.08f);
        section.addView(saved,new LinearLayout.LayoutParams(0,34,1));
        TextView count=label(items.length()+" items",12,muted);
        section.addView(count);
        root.addView(section);

        ScrollView sc=new ScrollView(this);
        sc.setFillViewport(true);
        sc.setClipToPadding(false);
        sc.setPadding(0,4,0,8);
        list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0,0,0,8);
        sc.addView(list);
        root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));

        Button add=button("+   Add information");
        add.setTextSize(15);
        add.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams addp=params(-1,54);
        addp.topMargin=8;
        root.addView(add,addp);
        add.setOnClickListener(v->{pendingImage="";showAddDialog();});

        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int before,int count){}
            public void onTextChanged(CharSequence s,int st,int before,int count){renderItems(s.toString());}
            public void afterTextChanged(Editable e){}
        });
        setContentView(root);
        renderItems("");
    }

    private Button smallIconButton(String text){
        Button b=new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(0,0,0,0);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackground(round(card,16));
        return b;
    }

    private void renderItems(String query){
        if(list==null)return;
        list.removeAllViews();
        String q=query.toLowerCase().trim();
        int count=0;
        for(int i=items.length()-1;i>=0;i--){
            JSONObject item=items.optJSONObject(i);
            if(item==null)continue;
            String hay=(item.optString("name")+" "+item.optString("category")+" "+item.optString("tags")+" "+item.optString("body")).toLowerCase();
            if(!hay.contains(q))continue;
            count++;
            LinearLayout box=new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(14,14,14,14);
            box.setBackground(round(card,20));
            LinearLayout.LayoutParams bp=params(-1,-2);
            bp.bottomMargin=10;
            list.addView(box,bp);

            LinearLayout row=new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView symbol=label(item.optString("image","").isEmpty()?"▤":"▧",21,fg);
            symbol.setGravity(Gravity.CENTER);
            symbol.setBackground(round(accent,14));
            row.addView(symbol,params(46,46));
            LinearLayout names=new LinearLayout(this);
            names.setOrientation(LinearLayout.VERTICAL);
            names.setPadding(12,0,0,0);
            TextView nm=label(item.optString("name","Untitled"),16,fg);
            nm.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
            names.addView(nm);
            names.addView(label(item.optString("category","Other")+"  ·  "+item.optString("date",""),12,muted));
            row.addView(names,new LinearLayout.LayoutParams(0,-2,1));
            row.addView(label("›",24,muted));
            box.addView(row);
            if(!item.optString("body","").isEmpty()){
                TextView preview=label(item.optString("body"),13,muted);
                preview.setMaxLines(2);
                preview.setPadding(58,7,0,0);
                box.addView(preview);
            }
            final int idx=i;
            box.setOnClickListener(v->showDetails(idx));
        }
        if(count==0){
            LinearLayout empty=new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(20,32,20,32);
            empty.setBackground(round(card,22));
            TextView icon=label(q.isEmpty()?"＋":"⌕",30,fg);
            icon.setGravity(Gravity.CENTER);
            empty.addView(icon,params(-1,46));
            TextView title=label(q.isEmpty()?"Your vault starts here":"No results found",17,fg);
            title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tp=params(-1,-2);tp.topMargin=8;empty.addView(title,tp);
            TextView hint=label(q.isEmpty()?"Add your first document, card or note.":"Try another name, tag or keyword.",13,muted);
            hint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hp=params(-1,-2);hp.topMargin=5;empty.addView(hint,hp);
            list.addView(empty,params(-1,-2));
        }
    }

    private void showAddDialog(){
        applyColors();
        final Dialog dialog=new Dialog(this);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(22,22,22,18);panel.setBackground(round(card,28));
        TextView eyebrow=label("PERSONAL VAULT  /  NEW ITEM",10,muted);eyebrow.setLetterSpacing(.1f);panel.addView(eyebrow);
        TextView title=label("Save something important",23,fg);title.setTypeface(null,android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tp=params(-1,-2);tp.topMargin=6;panel.addView(title,tp);
        TextView subtitle=label("Documents, details and notes. All offline.",13,muted);
        LinearLayout.LayoutParams sp=params(-1,-2);sp.topMargin=4;panel.addView(subtitle,sp);
        ScrollView scroll=new ScrollView(this);
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(0,10,0,8);
        draftName=field("e.g. Aadhaar card");draftCat=field("Identity, Banking, Personal…");
        draftTags=field("Tags, separated by commas");draftBody=field("Text, number or notes");
        draftBody.setMinLines(3);draftBody.setGravity(Gravity.TOP);
        form.addView(fieldLabel("NAME"));form.addView(draftName);
        form.addView(fieldLabel("CATEGORY"));form.addView(draftCat);
        form.addView(fieldLabel("TAGS"));form.addView(draftTags);
        form.addView(fieldLabel("DETAILS"));form.addView(draftBody);
        Button image=smallAction("＋   Add image",bg,fg);
        LinearLayout.LayoutParams ip=params(-1,48);ip.topMargin=10;form.addView(image,ip);
        imageStatus=label(pendingImage.isEmpty()?"No image attached":"Image attached  ✓",12,muted);
        LinearLayout.LayoutParams isp=params(-1,-2);isp.topMargin=5;form.addView(imageStatus,isp);
        image.setOnClickListener(v->{pickingImage=true;imagePicker.launch("image/*");});
        scroll.addView(form);LinearLayout.LayoutParams scp=params(-1,0);scp.weight=1;scp.topMargin=8;panel.addView(scroll,scp);
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel=smallAction("Cancel",bg,fg),save=smallAction("Save item",accent,android.graphics.Color.parseColor("#171814"));
        LinearLayout.LayoutParams acp=new LinearLayout.LayoutParams(0,50,1);acp.rightMargin=8;actions.addView(cancel,acp);
        actions.addView(save,new LinearLayout.LayoutParams(0,50,1));
        LinearLayout.LayoutParams actp=params(-1,-2);actp.topMargin=10;panel.addView(actions,actp);
        cancel.setOnClickListener(v->{pendingImage="";dialog.dismiss();});
        save.setOnClickListener(v->{
            String nm=draftName.getText().toString().trim();
            if(nm.isEmpty()){draftName.setError("Enter a name");return;}
            try{
                JSONObject obj=new JSONObject();obj.put("name",nm);
                obj.put("category",draftCat.getText().toString().trim().isEmpty()?"Other":draftCat.getText().toString().trim());
                obj.put("tags",draftTags.getText().toString());obj.put("body",draftBody.getText().toString());
                obj.put("image",pendingImage);obj.put("date",new java.text.SimpleDateFormat("dd MMM yyyy",java.util.Locale.getDefault()).format(new java.util.Date()));
                items.put(obj);saveData();pendingImage="";dialog.dismiss();showHome();
            }catch(Exception e){toast("Could not save item");}
        });
        dialog.setContentView(panel);dialog.show();
        android.view.Window w=dialog.getWindow();
        if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.92),(int)(getResources().getDisplayMetrics().heightPixels*.82));w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
    }
    private TextView fieldLabel(String text){
        TextView t=label(text,10,muted);t.setLetterSpacing(.1f);
        LinearLayout.LayoutParams p=params(-1,-2);p.topMargin=10;p.bottomMargin=3;t.setLayoutParams(p);return t;
    }
    private Button smallAction(String text,int color,int textColor){
        Button b=new Button(this);b.setText(text);b.setTextSize(14);b.setAllCaps(false);
        b.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);b.setTextColor(textColor);
        b.setBackground(round(color,16));b.setPadding(8,0,8,0);return b;
    }
    private void showDetails(int idx){
        JSONObject o=items.optJSONObject(idx);if(o==null)return;applyColors();
        final Dialog dialog=new Dialog(this);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(22,22,22,18);panel.setBackground(round(card,28));
        TextView eyebrow=label(o.optString("category","Other").toUpperCase(java.util.Locale.ROOT)+"  /  SAVED ITEM",10,muted);eyebrow.setLetterSpacing(.1f);panel.addView(eyebrow);
        TextView title=label(o.optString("name","Untitled"),24,fg);title.setTypeface(null,android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tp=params(-1,-2);tp.topMargin=6;panel.addView(title,tp);
        ScrollView scroll=new ScrollView(this);LinearLayout details=new LinearLayout(this);details.setOrientation(LinearLayout.VERTICAL);
        if(!o.optString("image","").isEmpty())try{byte[] bytes=Base64.decode(o.optString("image"),Base64.DEFAULT);ImageView im=new ImageView(this);im.setImageBitmap(BitmapFactory.decodeByteArray(bytes,0,bytes.length));im.setAdjustViewBounds(true);im.setScaleType(ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams p=params(-1,190);p.topMargin=14;details.addView(im,p);}catch(Exception ignored){}
        TextView body=label(o.optString("body","No text saved"),15,fg);body.setTextIsSelectable(true);body.setPadding(14,14,14,14);body.setBackground(round(bg,16));LinearLayout.LayoutParams bp=params(-1,-2);bp.topMargin=14;details.addView(body,bp);
        TextView tags=label("TAGS   "+(o.optString("tags","").isEmpty()?"None":o.optString("tags")),12,muted);LinearLayout.LayoutParams tagp=params(-1,-2);tagp.topMargin=12;details.addView(tags,tagp);
        TextView date=label("Saved "+o.optString("date",""),12,muted);LinearLayout.LayoutParams dp=params(-1,-2);dp.topMargin=6;details.addView(date,dp);
        scroll.addView(details);LinearLayout.LayoutParams scp=params(-1,0);scp.weight=1;scp.topMargin=8;panel.addView(scroll,scp);
        LinearLayout actions=new LinearLayout(this);Button close=smallAction("Close",bg,fg),del=smallAction("Delete item",android.graphics.Color.parseColor("#F3D8D3"),android.graphics.Color.parseColor("#842D25"));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,48,1);ap.rightMargin=8;actions.addView(close,ap);actions.addView(del,new LinearLayout.LayoutParams(0,48,1));
        LinearLayout.LayoutParams actp=params(-1,-2);actp.topMargin=14;panel.addView(actions,actp);
        close.setOnClickListener(v->dialog.dismiss());
        del.setOnClickListener(v->new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Delete this item?").setMessage("This cannot be undone.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{items.remove(idx);saveData();dialog.dismiss();showHome();}).show());
        dialog.setContentView(panel);dialog.show();android.view.Window w=dialog.getWindow();
        if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.92),(int)(getResources().getDisplayMetrics().heightPixels*.72));}
    }
    private EditText field(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(15);e.setPadding(14,10,14,10);e.setTextColor(fg);e.setHintTextColor(muted);e.setSingleLine(false);e.setBackground(round(bg,14));e.setElevation(0);return e;}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(android.graphics.Color.parseColor("#171814"));b.setBackground(round(accent,22));return b;}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private android.graphics.drawable.GradientDrawable round(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private LinearLayout.LayoutParams params(int w,int h){return new LinearLayout.LayoutParams(w<0?w:w,h<0?h:h);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override public void onBackPressed(){if(unlocked){unlocked=false;showLock();}else super.onBackPressed();}
    @Override protected void onStop(){super.onStop();if(unlocked && !pickingImage){unlocked=false;relockOnResume=true;}}
    @Override protected void onResume(){super.onResume();if(relockOnResume){relockOnResume=false;showLock();}}
}