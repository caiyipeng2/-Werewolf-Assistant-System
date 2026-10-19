package com.langyu.assistant;
import android.content.Context;
import org.vosk.Model;
import java.io.*;
import java.util.zip.*;

final class OfflineModel {
    static volatile Model cached;
    static synchronized Model get(Context c)throws IOException{
        if(cached!=null)return cached;
        File root=new File(c.getFilesDir(),"offline-cn-022");root.mkdirs();File ready=new File(root,".ready");
        if(!ready.exists()){
            byte[] buffer=new byte[65536];
            try(ZipInputStream zip=new ZipInputStream(c.getAssets().open("asr-model.zip"))){ZipEntry e;while((e=zip.getNextEntry())!=null){String name=e.getName();int slash=name.indexOf('/');if(slash<0)continue;name=name.substring(slash+1);if(name.isEmpty())continue;File target=new File(root,name);if(!target.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("模型路径无效");if(e.isDirectory())target.mkdirs();else{target.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(target)){int n;while((n=zip.read(buffer))>0)out.write(buffer,0,n);}}}}
            try(FileOutputStream out=new FileOutputStream(ready)){out.write(1);}
        }
        cached=new Model(root.getAbsolutePath());return cached;
    }
}
