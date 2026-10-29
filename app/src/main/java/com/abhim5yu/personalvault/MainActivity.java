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
    private String activeCategory = "All";
    private String pendingImage = "";
    private int bg, card, fg, muted, accent;
    private ActivityResultLauncher<String> imagePicker;
    private ActivityResultLauncher<String[]> filePicker;
    private ActivityResultLauncher<String> exportPicker;
    private byte[] exportBytes; private String exportMime="application/octet-stream";
    private String pendingFile = "", pendingFileName = "", pendingFileMime = "";
    private EditText filePassword;
    private LinearLayout pairsContainer;
    private final java.util.ArrayList<EditText[]> valuePairs = new java.util.ArrayList<>();
    private EditText draftName, draftTags, draftBody;
    private Spinner draftCat;
    private TextView imageStatus;
    private boolean updateChecked = false;
    private static final String UPDATE_API="https://api.github.com/repos/abhim5yu/Valult/releases/latest";
    private static final String UPDATE_APK="https://github.com/abhim5yu/Valult/releases/latest/download/PersonalVault.apk";

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
        exportPicker = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {if(uri==null||exportBytes==null)return;try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out!=null)out.write(exportBytes);toast("Attachment exported");}catch(Exception e){toast("Could not export attachment");}finally{exportBytes=null;}});
        filePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            pickingImage=false;
            if(uri==null)return;
            try(InputStream in=getContentResolver().openInputStream(uri); ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192]; int n; while((n=in.read(buffer))>0){out.write(buffer,0,n);if(out.size()>8*1024*1024){toast("Files must be 8 MB or smaller");return;}}
                pendingFile=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
                pendingFileName=getFileName(uri); pendingFileMime=getContentResolver().getType(uri);
                if(imageStatus!=null)imageStatus.setText("Attached: "+pendingFileName);
            }catch(Exception e){toast("Could not attach file");}
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
        root.setGravity(Gravity.CENTER); root.setPadding(dp(32),dp(32),dp(32),dp(32)); root.setBackgroundColor(bg);
        TextView mark = label("◈", 40, android.graphics.Color.parseColor("#171814")); mark.setGravity(Gravity.CENTER); mark.setBackground(round(accent,32));
        LinearLayout.LayoutParams markp = params(84,84); markp.bottomMargin=dp(22); root.addView(mark, markp);
        TextView title = label("Personal Vault", 30, fg); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub = label("Your essentials. Only yours.", 15, muted); sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subp = params(-1,-2); subp.topMargin=dp(6); subp.bottomMargin=dp(36); root.addView(sub, subp);
        Button unlock = button("Unlock with fingerprint"); unlock.setTextSize(16); unlock.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); root.addView(unlock, params(-1,56));
        TextView note=label("Stored on this device • No account • No cloud",12,muted); note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams notep = params(-1,-2); notep.topMargin=dp(18); root.addView(note, notep);
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
        root.setPadding(dp(20),dp(18),dp(20),dp(14));

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView brand=label("PERSONAL VAULT  ·  OFFLINE",11,muted);
        brand.setLetterSpacing(.08f);
        titles.addView(brand);
        TextView heading=label("Personal Vault",34,fg);
        heading.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        titles.addView(heading);
        top.addView(titles,lp(0,-2,1));

        Button theme=smallIconButton(dark?"☀":"☾");
        top.addView(theme,params(44,44));
        theme.setOnClickListener(v->{dark=!dark;showHome();});
        Button upd=smallIconButton("↻");
        LinearLayout.LayoutParams up=params(44,44);up.leftMargin=dp(8);top.addView(upd,up);
        upd.setOnClickListener(v->checkForUpdate(true));
        Button lock=smallIconButton("⌑");
        LinearLayout.LayoutParams lp=params(44,44);lp.leftMargin=dp(8);top.addView(lock,lp);
        lock.setOnClickListener(v->{unlocked=false;showLock();});
        root.addView(top);

        TextView tagline=label("Your important information, kept private.",17,muted);
        LinearLayout.LayoutParams tagp=params(-1,30);tagp.topMargin=dp(2);root.addView(tagline,tagp);

        Button add=button("＋   Add information");
        add.setTextSize(15);
        add.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams addp=params(-1,54);
        addp.topMargin=dp(8);
        root.addView(add,addp);
        add.setOnClickListener(v->{pendingImage="";showAddDialog();});

        TextView savedIntro=label("YOUR FILES",13,muted);
        savedIntro.setLetterSpacing(.12f);
        LinearLayout.LayoutParams introParams=params(-1,-2);introParams.topMargin=dp(20);introParams.bottomMargin=dp(6);root.addView(savedIntro,introParams);

        LinearLayout section=new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        TextView saved=label("Saved files",22,fg);
        saved.setLetterSpacing(.08f);
        section.addView(saved,lp(0,-2,1));
        TextView count=label(items.length()+" items",15,muted);
        section.addView(count);
        root.addView(section);

        search=new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(18);
        search.setHint("Search saved files, names, notes…");
        search.setPadding(dp(16),0,dp(16),0);
        search.setBackground(round(card,16));
        search.setTextColor(fg);
        search.setHintTextColor(muted);
        search.setElevation(0);
        root.addView(search,params(-1,50));

        HorizontalScrollView horizontal=new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout chips=new LinearLayout(this);
        chips.setPadding(0,dp(12),0,dp(14));
        String[] cats={"All","Identity","Banking","Personal","Other"};
        for(String category:cats){
            TextView chip=label(category,13,fg);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(15),0,dp(15),0);
            chip.setBackground(round(category.equals(activeCategory)?accent:card,18));
            LinearLayout.LayoutParams cp=params(-2,34);
            cp.setMargins(0,0,dp(8),0);
            chips.addView(chip,cp);
            chip.setOnClickListener(v->{
                activeCategory=category;
                for(int j=0;j<chips.getChildCount();j++){
                    View child=chips.getChildAt(j);
                    if(child instanceof TextView){
                        TextView t=(TextView)child;
                        t.setBackground(round(t.getText().toString().equals(activeCategory)?accent:card,18));
                    }
                }
                renderItems(search.getText().toString());
            });
        }
        horizontal.addView(chips);
        root.addView(horizontal);


        ScrollView sc=new ScrollView(this);
        sc.setFillViewport(true);
        sc.setClipToPadding(false);
        sc.setPadding(0,dp(4),0,dp(8));
        list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0,0,0,dp(8));
        sc.addView(list);
        root.addView(sc,lp(-1,0,1));

        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int before,int count){}
            public void onTextChanged(CharSequence s,int st,int before,int count){renderItems(s.toString());}
            public void afterTextChanged(Editable e){}
        });
        setContentView(root);
        renderItems("");
        if(!updateChecked){updateChecked=true;checkForUpdate(false);}
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
            String hay=(item.optString("name")+" "+item.optString("category")+" "+item.optString("tags")+" "+item.optString("body")+" "+item.optString("fileName")+" "+item.optString("fields")).toLowerCase();
            if(!hay.contains(q))continue;
            if(!activeCategory.equals("All") && !item.optString("category","Other").equalsIgnoreCase(activeCategory))continue;
            count++;
            LinearLayout box=new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(14),dp(14),dp(14),dp(14));
            box.setBackground(round(card,20));
            LinearLayout.LayoutParams bp=params(-1,-2);
            bp.bottomMargin=dp(10);
            list.addView(box,bp);

            LinearLayout row=new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            String savedImage=item.optString("image","");
            if(!savedImage.isEmpty()){
                try{
                    byte[] thumbBytes=Base64.decode(savedImage,Base64.DEFAULT);
                    Bitmap thumb=BitmapFactory.decodeByteArray(thumbBytes,0,thumbBytes.length);
                    ImageView preview=new ImageView(this);
                    preview.setImageBitmap(thumb);
                    preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    preview.setBackground(round(accent,14));
                    preview.setClipToOutline(true);
                    row.addView(preview,params(46,46));
                }catch(Exception ex){
                    TextView symbol=label("▧",21,fg);symbol.setGravity(Gravity.CENTER);symbol.setBackground(round(accent,14));row.addView(symbol,params(46,46));
                }
            }else{
                TextView symbol=label("▤",21,fg);symbol.setGravity(Gravity.CENTER);symbol.setBackground(round(accent,14));row.addView(symbol,params(46,46));
            }
            LinearLayout names=new LinearLayout(this);
            names.setOrientation(LinearLayout.VERTICAL);
            names.setPadding(dp(12),0,0,0);
            TextView nm=label(item.optString("name","Untitled"),21,fg);
            nm.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
            names.addView(nm);
            names.addView(label(item.optString("category","Other")+"  ·  "+item.optString("date",""),15,muted));
            row.addView(names,lp(0,-2,1));
            row.addView(label("›",24,muted));
            box.addView(row);
            if(!item.optString("body","").isEmpty()){
                TextView preview=label(item.optString("body"),15,muted);
                preview.setMaxLines(2);
                preview.setPadding(dp(58),dp(7),0,0);
                box.addView(preview);
            }
            final int idx=i;
            box.setOnClickListener(v->showDetails(idx));
        }
        if(count==0){
            LinearLayout empty=new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20),dp(32),dp(20),dp(32));
            empty.setBackground(round(dark?card:android.graphics.Color.parseColor("#EEF5D9"),24));
            TextView icon=label(q.isEmpty()?"▤":"⌕",34,fg);
            icon.setGravity(Gravity.CENTER);
            empty.addView(icon,params(-1,46));
            TextView title=label(q.isEmpty()?(activeCategory.equals("All")?"Your essentials, all in one place":"Nothing in "+activeCategory):"No results found",19,fg);
            title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tp=params(-1,-2);tp.topMargin=dp(8);empty.addView(title,tp);
            TextView hint=label(q.isEmpty()?(activeCategory.equals("All")?"Save your Aadhaar, PAN, cards, images and important notes.":"Add an item in this category, or choose All."):"Try another name, tag or keyword.",14,muted);
            hint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hp=params(-1,-2);hp.topMargin=dp(5);empty.addView(hint,hp);
            list.addView(empty,params(-1,-2));
        }
    }

    private void showAddDialog(){
        applyColors();
        final Dialog dialog=new Dialog(this);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22),dp(22),dp(22),dp(18));panel.setBackground(round(card,28));
        TextView eyebrow=label("PERSONAL VAULT  /  NEW ITEM",10,muted);eyebrow.setLetterSpacing(.1f);panel.addView(eyebrow);
        TextView title=label("Save something important",23,fg);title.setTypeface(null,android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tp=params(-1,-2);tp.topMargin=dp(6);panel.addView(title,tp);
        TextView subtitle=label("Documents, details and notes. All offline.",13,muted);
        LinearLayout.LayoutParams sp=params(-1,-2);sp.topMargin=dp(4);panel.addView(subtitle,sp);
        ScrollView scroll=new ScrollView(this);
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(0,dp(10),0,dp(8));
        draftName=field("e.g. Aadhaar card");
        draftCat=new Spinner(this);
        String[] categoryOptions={"Identity","Banking","Personal","Other"};
        ArrayAdapter<String> categoryAdapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,categoryOptions){
            @Override public View getView(int position,View convertView,android.view.ViewGroup parent){
                TextView t=(TextView)super.getView(position,convertView,parent);t.setTextColor(fg);t.setTextSize(15);return t;
            }
            @Override public View getDropDownView(int position,View convertView,android.view.ViewGroup parent){
                TextView t=(TextView)super.getDropDownView(position,convertView,parent);t.setTextColor(fg);t.setBackgroundColor(card);t.setPadding(dp(16),dp(12),dp(16),dp(12));t.setTextSize(15);return t;
            }
        };
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        draftCat.setAdapter(categoryAdapter);
        draftCat.setBackground(round(bg,14));
        draftCat.setPadding(dp(10),0,dp(10),0);
        draftTags=field("e.g. personal, important");draftBody=field("Extra notes (optional)");
        draftBody.setMinLines(2);draftBody.setGravity(Gravity.TOP);
        valuePairs.clear();
        pairsContainer=new LinearLayout(this);pairsContainer.setOrientation(LinearLayout.VERTICAL);
        addValuePair("Aadhaar","");
        form.addView(fieldLabel("NAME"));form.addView(draftName);
        form.addView(fieldLabel("CATEGORY"));form.addView(draftCat,params(-1,48));
        form.addView(fieldLabel("TEXT DETAILS  ·  TITLE + NUMBER"));
        form.addView(pairsContainer);
        Button addPair=smallAction("＋  Add text field",bg,fg);LinearLayout.LayoutParams pairp=params(-1,48);pairp.topMargin=dp(8);form.addView(addPair,pairp);
        addPair.setOnClickListener(v->addValuePair("",""));
        form.addView(fieldLabel("NOTES (OPTIONAL)"));form.addView(draftBody);
        form.addView(fieldLabel("ATTACHMENT"));
        LinearLayout fileBox=new LinearLayout(this);fileBox.setOrientation(LinearLayout.VERTICAL);
        fileBox.setPadding(dp(10),dp(10),dp(10),dp(12));fileBox.setBackground(outlined(bg,18));
        LinearLayout drop=new LinearLayout(this);drop.setOrientation(LinearLayout.VERTICAL);drop.setGravity(Gravity.CENTER);
        drop.setPadding(dp(12),dp(22),dp(12),dp(22));drop.setBackground(dashed(14));
        TextView dropIcon=label("＋",32,accent);dropIcon.setGravity(Gravity.CENTER);
        TextView dropTitle=label("Tap to upload image or file",15,fg);dropTitle.setGravity(Gravity.CENTER);dropTitle.setTypeface(null,android.graphics.Typeface.BOLD);
        TextView dropSub=label("IMAGE  ·  PDF  ·  ZIP  ·  OTHER",10,muted);dropSub.setGravity(Gravity.CENTER);dropSub.setLetterSpacing(.08f);
        LinearLayout.LayoutParams dsp=params(-1,-2);dsp.topMargin=dp(4);
        drop.addView(dropIcon,params(-1,-2));drop.addView(dropTitle,params(-1,-2));drop.addView(dropSub,dsp);
        fileBox.addView(drop,params(-1,-2));
        imageStatus=label(pendingFile.isEmpty()?"No file selected":"Attached: "+pendingFileName,14,muted);imageStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams isp=params(-1,-2);isp.topMargin=dp(10);fileBox.addView(imageStatus,isp);
        filePassword=innerField("Password for protected file (optional)");filePassword.setSingleLine(true);filePassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams fpp=params(-1,50);fpp.topMargin=dp(10);fileBox.addView(filePassword,fpp);
        form.addView(fileBox,params(-1,-2));
        drop.setOnClickListener(v->{pickingImage=true;filePicker.launch(new String[]{"image/*","application/pdf","application/zip","application/x-zip-compressed","*/*"});});
        scroll.addView(form);LinearLayout.LayoutParams scp=params(-1,0);scp.weight=1;scp.topMargin=dp(8);panel.addView(scroll,scp);
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel=smallAction("Cancel",bg,fg),save=smallAction("Save item",accent,android.graphics.Color.parseColor("#171814"));
        LinearLayout.LayoutParams acp=lp(0,50,1);acp.rightMargin=dp(8);actions.addView(cancel,acp);
        actions.addView(save,lp(0,50,1));
        LinearLayout.LayoutParams actp=params(-1,-2);actp.topMargin=dp(10);panel.addView(actions,actp);
        cancel.setOnClickListener(v->{pendingImage="";pendingFile="";pendingFileName="";pendingFileMime="";dialog.dismiss();});
        save.setOnClickListener(v->{
            String nm=draftName.getText().toString().trim();
            if(nm.isEmpty()){draftName.setError("Enter a name");return;}
            try{
                JSONObject obj=new JSONObject();obj.put("name",nm);
                obj.put("category",draftCat.getSelectedItem()==null?"Other":draftCat.getSelectedItem().toString());
                obj.put("tags",draftTags.getText().toString());obj.put("body",draftBody.getText().toString());
                JSONArray fields=new JSONArray();for(EditText[] pair:valuePairs){String key=pair[0].getText().toString().trim(),value=pair[1].getText().toString().trim();if(!key.isEmpty()||!value.isEmpty()){JSONObject fieldObj=new JSONObject();fieldObj.put("label",key);fieldObj.put("value",value);fields.put(fieldObj);}}
                obj.put("fields",fields);obj.put("image","");obj.put("fileData",pendingFile);obj.put("fileName",pendingFileName);obj.put("fileMime",pendingFileMime);obj.put("filePassword",filePassword.getText().toString());obj.put("date",new java.text.SimpleDateFormat("dd MMM yyyy",java.util.Locale.getDefault()).format(new java.util.Date()));
                items.put(obj);saveData();pendingImage="";pendingFile="";pendingFileName="";pendingFileMime="";dialog.dismiss();showHome();
            }catch(Exception e){toast("Could not save item");}
        });
        dialog.setContentView(panel);dialog.show();
        android.view.Window w=dialog.getWindow();
        if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.92),(int)(getResources().getDisplayMetrics().heightPixels*.82));w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
    }
    private void addValuePair(String labelValue,String valueValue){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.HORIZONTAL);box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(8),dp(8),dp(8),dp(8));box.setBackground(outlined(bg,18));
        EditText key=innerField("Title");EditText value=innerField("Number");
        key.setSingleLine(true);value.setSingleLine(true);key.setTextSize(16);value.setTextSize(16);
        key.setGravity(Gravity.CENTER_VERTICAL);value.setGravity(Gravity.CENTER_VERTICAL);
        if(!labelValue.isEmpty())key.setText(labelValue);if(!valueValue.isEmpty())value.setText(valueValue);
        LinearLayout.LayoutParams ka=lp(0,50,1);ka.rightMargin=dp(8);box.addView(key,ka);
        box.addView(value,lp(0,50,1));
        LinearLayout.LayoutParams rp=params(-1,-2);rp.topMargin=dp(8);pairsContainer.addView(box,rp);valuePairs.add(new EditText[]{key,value});
    }
    private EditText innerField(String hint){EditText e=field(hint);e.setBackground(round(card,12));return e;}
    private android.graphics.drawable.GradientDrawable outlined(int color,int radius){
        android.graphics.drawable.GradientDrawable d=round(color,radius);d.setStroke(dp(1),(muted&0x00FFFFFF)|0x4D000000);return d;}
    private android.graphics.drawable.GradientDrawable dashed(int radius){
        android.graphics.drawable.GradientDrawable d=round(card,radius);d.setStroke(dp(2),(accent&0x00FFFFFF)|0xB3000000,dp(7),dp(5));return d;}
    private String getFileName(Uri uri){String name="attachment";try(android.database.Cursor cursor=getContentResolver().query(uri,null,null,null,null)){if(cursor!=null&&cursor.moveToFirst()){int i=cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);if(i>=0)name=cursor.getString(i);}}catch(Exception ignored){}return name;}
    private TextView fieldLabel(String text){
        TextView t=label(text,10,muted);t.setLetterSpacing(.1f);
        LinearLayout.LayoutParams p=params(-1,-2);p.topMargin=dp(10);p.bottomMargin=dp(3);t.setLayoutParams(p);return t;
    }
    private Button smallAction(String text,int color,int textColor){
        Button b=new Button(this);b.setText(text);b.setTextSize(14);b.setAllCaps(false);
        b.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);b.setTextColor(textColor);
        b.setBackground(round(color,16));b.setPadding(dp(8),0,dp(8),0);return b;
    }
    private void showDetails(int idx){
        JSONObject o=items.optJSONObject(idx);if(o==null)return;applyColors();
        final Dialog dialog=new Dialog(this);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(22),dp(22),dp(22),dp(18));panel.setBackground(round(card,28));
        TextView eyebrow=label(o.optString("category","Other").toUpperCase(java.util.Locale.ROOT)+"  /  SAVED ITEM",10,muted);eyebrow.setLetterSpacing(.1f);panel.addView(eyebrow);
        TextView title=label(o.optString("name","Untitled"),24,fg);title.setTypeface(null,android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tp=params(-1,-2);tp.topMargin=dp(6);panel.addView(title,tp);
        ScrollView scroll=new ScrollView(this);LinearLayout details=new LinearLayout(this);details.setOrientation(LinearLayout.VERTICAL);
        if(!o.optString("image","").isEmpty())try{byte[] bytes=Base64.decode(o.optString("image"),Base64.DEFAULT);ImageView im=new ImageView(this);im.setImageBitmap(BitmapFactory.decodeByteArray(bytes,0,bytes.length));im.setAdjustViewBounds(true);im.setScaleType(ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams p=params(-1,190);p.topMargin=dp(14);details.addView(im,p);}catch(Exception ignored){}
        JSONArray fields=o.optJSONArray("fields");if(fields!=null)for(int i=0;i<fields.length();i++){JSONObject field=fields.optJSONObject(i);if(field==null)continue;LinearLayout pair=new LinearLayout(this);pair.setOrientation(LinearLayout.HORIZONTAL);pair.setPadding(dp(14),dp(14),dp(14),dp(14));pair.setBackground(round(bg,14));TextView key=label(field.optString("label","Field"),16,muted);TextView value=label(field.optString("value",""),17,fg);value.setTextIsSelectable(true);pair.addView(key,lp(0,-2,1));pair.addView(value,lp(0,-2,1));LinearLayout.LayoutParams pp=params(-1,-2);pp.topMargin=dp(7);details.addView(pair,pp);}
        if(!o.optString("body","").isEmpty()){TextView body=label(o.optString("body"),16,fg);body.setTextIsSelectable(true);body.setPadding(dp(14),dp(14),dp(14),dp(14));body.setBackground(round(bg,16));LinearLayout.LayoutParams bp=params(-1,-2);bp.topMargin=dp(10);details.addView(body,bp);}
        if(!o.optString("fileData","").isEmpty()){TextView attachment=label("▤  "+o.optString("fileName","Attachment")+"\nSaved offline · "+o.optString("fileMime","file"),16,fg);attachment.setPadding(dp(14),dp(14),dp(14),dp(14));attachment.setBackground(round(accent,16));LinearLayout.LayoutParams ap=params(-1,-2);ap.topMargin=dp(10);details.addView(attachment,ap);Button export=smallAction("Export attachment",bg,fg);details.addView(export,params(-1,48));export.setOnClickListener(v->exportAttachment(o));}
        if(!o.optString("filePassword","").isEmpty()){TextView pw=label("File password: "+o.optString("filePassword"),15,fg);pw.setTextIsSelectable(true);pw.setPadding(dp(14),dp(12),dp(14),dp(12));pw.setBackground(round(bg,14));LinearLayout.LayoutParams pwp=params(-1,-2);pwp.topMargin=dp(8);details.addView(pw,pwp);}
        TextView tags=label("TAGS   "+(o.optString("tags","").isEmpty()?"None":o.optString("tags")),12,muted);LinearLayout.LayoutParams tagp=params(-1,-2);tagp.topMargin=dp(12);details.addView(tags,tagp);
        TextView date=label("Saved "+o.optString("date",""),12,muted);LinearLayout.LayoutParams dp=params(-1,-2);dp.topMargin=dp(6);details.addView(date,dp);
        scroll.addView(details);LinearLayout.LayoutParams scp=params(-1,0);scp.weight=1;scp.topMargin=dp(8);panel.addView(scroll,scp);
        LinearLayout actions=new LinearLayout(this);Button close=smallAction("Close",bg,fg),del=smallAction("Delete item",android.graphics.Color.parseColor("#F3D8D3"),android.graphics.Color.parseColor("#842D25"));
        LinearLayout.LayoutParams ap=lp(0,48,1);ap.rightMargin=dp(8);actions.addView(close,ap);actions.addView(del,lp(0,48,1));
        LinearLayout.LayoutParams actp=params(-1,-2);actp.topMargin=dp(14);panel.addView(actions,actp);
        close.setOnClickListener(v->dialog.dismiss());
        del.setOnClickListener(v->new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Delete this item?").setMessage("This cannot be undone.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{items.remove(idx);saveData();dialog.dismiss();showHome();}).show());
        dialog.setContentView(panel);dialog.show();android.view.Window w=dialog.getWindow();
        if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.92),(int)(getResources().getDisplayMetrics().heightPixels*.72));}
    }
    private void checkForUpdate(final boolean manual){
        new Thread(()->{
            try{
                java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL(UPDATE_API).openConnection();
                c.setConnectTimeout(8000);c.setReadTimeout(8000);
                c.setRequestProperty("Accept","application/vnd.github+json");
                if(c.getResponseCode()!=200)throw new Exception("http "+c.getResponseCode());
                ByteArrayOutputStream buf=new ByteArrayOutputStream();
                try(InputStream in=c.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))>0)buf.write(b,0,n);}
                JSONObject j=new JSONObject(new String(buf.toByteArray(),StandardCharsets.UTF_8));
                final int latest=Integer.parseInt(j.optString("tag_name","").replaceAll("[^0-9]",""));
                final int current=(int)getPackageManager().getPackageInfo(getPackageName(),0).getLongVersionCode()-100;
                runOnUiThread(()->{
                    if(latest>current)showUpdateDialog(latest);
                    else if(manual)toast("You have the latest version");
                });
            }catch(Exception e){
                if(manual)runOnUiThread(()->toast("Could not check for updates"));
            }
        }).start();
    }
    private void showUpdateDialog(int build){
        if(isFinishing())return;
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Update available")
          .setMessage("A newer version (build "+build+") is ready. Download it, then open the file to install it over this app. Your saved items stay.")
          .setNegativeButton("Later",null)
          .setPositiveButton("Download",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(UPDATE_APK)));}catch(Exception e){toast("Could not open the download link");}})
          .show();
    }
    private void exportAttachment(JSONObject o){try{exportBytes=Base64.decode(o.optString("fileData"),Base64.DEFAULT);exportMime=o.optString("fileMime","application/octet-stream");exportPicker.launch(o.optString("fileName","attachment"));}catch(Exception e){toast("Could not export attachment");}}
    private EditText field(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(15);e.setPadding(dp(14),dp(12),dp(14),dp(12));e.setTextColor(fg);e.setHintTextColor(muted);e.setSingleLine(false);e.setBackground(round(bg,14));e.setElevation(0);return e;}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(android.graphics.Color.parseColor("#171814"));b.setBackground(round(accent,22));return b;}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private android.graphics.drawable.GradientDrawable round(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w>0?dp(w):w,h>0?dp(h):h);}
    private LinearLayout.LayoutParams lp(int w,int h,float weight){return new LinearLayout.LayoutParams(w>0?dp(w):w,h>0?dp(h):h,weight);}
    private LinearLayout.LayoutParams params(int w,int h){return lp(w,h);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override public void onBackPressed(){if(unlocked){unlocked=false;showLock();}else super.onBackPressed();}
    @Override protected void onStop(){
        super.onStop();
        // Don't replace the screen while Android is showing the system document picker.
        // Relock when returning from a genuine background transition instead.
        if(unlocked && !pickingImage) relockOnResume=true;
    }
    @Override protected void onResume(){
        super.onResume();
        if(relockOnResume && !pickingImage){
            relockOnResume=false;
            unlocked=false;
            showLock();
        }
    }
}