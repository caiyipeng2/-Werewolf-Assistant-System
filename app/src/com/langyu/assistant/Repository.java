package com.langyu.assistant;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** All match mutations happen on the UI looper; disk writes use atomic replacement. */
public final class Repository {
    private static Repository instance;
    public GameState state;
    private final Context context;
    private final File current, sessions, custom;
    public static synchronized Repository get(Context c) { if(instance==null)instance=new Repository(c.getApplicationContext()); return instance; }
    private Repository(Context c) {
        context=c; current=new File(c.getFilesDir(),"current.json"); sessions=new File(c.getFilesDir(),"sessions"); custom=new File(c.getFilesDir(),"boards.json"); sessions.mkdirs();
        try { state=decode(new JSONObject(read(current))); } catch(Exception e) { state=new GameState(); }
    }
    public synchronized void save() { write(current,encode(state).toString()); }
    public synchronized void archive() { write(new File(sessions,state.id+".json"),encode(state).toString()); save(); }
    public synchronized List<String[]> archives() {
        List<String[]> result=new ArrayList<>(); File[] files=sessions.listFiles(); if(files==null)return result;
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        for(File f:files)try { JSONObject j=new JSONObject(read(f)); result.add(new String[]{j.getString("id"),j.optString("boardName")+" · "+new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(f.lastModified()))+" · "+j.optJSONArray("speeches").length()+"条"}); }catch(Exception ignored){}
        return result;
    }
    public synchronized void load(String id) { if(!id.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("对局编号无效"); try{state=decode(new JSONObject(read(new File(sessions,id+".json"))));save();}catch(Exception e){throw new IllegalStateException("无法读取存档",e);} }
    public synchronized void deleteAll(){ File[] files=sessions.listFiles();if(files!=null)for(File f:files)f.delete();current.delete();custom.delete();state=new GameState();save(); }
    public synchronized String exportJson(){return encode(state).toString();}
    public synchronized String exportText(){
        StringBuilder b=new StringBuilder("狼语复盘 · ").append(state.boardName).append("\n第").append(state.day).append("天 · ").append(state.phase).append("\n配置：").append(String.join("、",state.roles)).append("\n\n发言记录\n");
        if(state.mySeat>0)b.append("本局本人：").append(state.mySeat).append("号 · ").append(state.myRole).append(state.screenLocked?"（已固定）":"").append("\n识别依据：").append(state.screenEvidence).append("\n\n");
        for(GameState.Speech s:state.speeches)b.append("第").append(s.day).append("天 ").append(s.speaker).append("号 ").append(new SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(new Date(s.timestamp))).append("\n").append(s.text).append("\n\n");
        b.append("事件\n");for(GameState.Event e:state.events)b.append("第").append(e.day).append("天 ").append(e.actor).append(" → ").append(e.target).append(" ").append(e.type).append(" ").append(e.detail).append("\n");
        b.append("\n人工确认及屏幕已读本人身份：").append(state.locks).append("\n启发式估计（非校准概率）\n");
        for(RuleEngine.Assessment a:RuleEngine.analyze(state))b.append(a.seat).append("号 ").append(a.probability).append("% ").append(a.label).append(" ").append(String.join("；",a.reasons)).append("\n");
        return b.append("\n话术草稿\n").append(state.draft).toString();
    }
    public static final class Board { public String id,name,notes;public int wolves;public String[] roles; public Board(){} }
    public synchronized List<Board> boards(){
        List<Board> list=new ArrayList<>();try(InputStream in=context.getAssets().open("boards.json")){parseBoards(new JSONArray(new String(bytes(in),StandardCharsets.UTF_8)),list);}catch(Exception ignored){}
        if(list.isEmpty()){ Board b=new Board();b.id="standard";b.name="预女猎白";b.notes="12人标准配置";b.wolves=4;b.roles=new String[]{"狼人","狼人","狼人","狼人","预言家","女巫","猎人","白痴","村民","村民","村民","村民"};list.add(b); }
        try{parseBoards(new JSONArray(read(custom)),list);}catch(Exception ignored){} return list;
    }
    public synchronized void addBoard(Board b){if(b.roles==null||b.roles.length<5||b.roles.length>20||b.wolves<1||b.wolves>=b.roles.length)throw new IllegalArgumentException("请填写5–20人且狼数小于总人数的配置");for(String role:b.roles)if(role==null||role.trim().isEmpty())throw new IllegalArgumentException("角色不能为空");List<Board> all=new ArrayList<>();try{parseBoards(new JSONArray(read(custom)),all);}catch(Exception ignored){}if(b.id==null)b.id="custom_"+System.currentTimeMillis();all.add(b);JSONArray j=new JSONArray();for(Board x:all)j.put(boardJson(x));write(custom,j.toString());}
    private static void parseBoards(JSONArray js,List<Board> list)throws JSONException{for(int i=0;i<js.length();i++){JSONObject j=js.getJSONObject(i);Board b=new Board();b.id=j.optString("id");b.name=j.getString("name");b.notes=j.optString("notes");b.wolves=j.getInt("wolves");JSONArray r=j.getJSONArray("roles");b.roles=new String[r.length()];for(int k=0;k<r.length();k++)b.roles[k]=r.getString(k);list.add(b);}}
    private static JSONObject boardJson(Board b){JSONObject j=new JSONObject();try{j.put("id",b.id).put("name",b.name).put("notes",b.notes).put("wolves",b.wolves).put("roles",new JSONArray(Arrays.asList(b.roles)));}catch(JSONException ignored){}return j;}
    static JSONObject encode(GameState s){JSONObject j=new JSONObject();try{
        j.put("version",2).put("id",s.id).put("boardName",s.boardName).put("phase",s.phase).put("draft",s.draft).put("day",s.day).put("speaker",s.speaker).put("wolfCount",s.wolfCount).put("roles",new JSONArray(Arrays.asList(s.roles)));
        j.put("mySeat",s.mySeat).put("myRole",s.myRole).put("screenLocked",s.screenLocked).put("screenEvidence",s.screenEvidence).put("screenRecognizedAt",s.screenRecognizedAt).put("screenBoardId",s.screenBoardId).put("screenAutoLockSeat",s.screenAutoLockSeat).put("screenAutoLockRole",s.screenAutoLockRole);
        JSONArray ss=new JSONArray();for(GameState.Speech x:s.speeches){JSONObject a=new JSONObject();a.put("id",x.id).put("day",x.day).put("speaker",x.speaker).put("text",x.text).put("timestamp",x.timestamp);ss.put(a);}j.put("speeches",ss);
        JSONArray es=new JSONArray();for(GameState.Event x:s.events){JSONObject a=new JSONObject();a.put("type",x.type).put("detail",x.detail).put("day",x.day).put("actor",x.actor).put("target",x.target).put("timestamp",x.timestamp);es.put(a);}j.put("events",es);
        JSONObject locks=new JSONObject();for(Map.Entry<Integer,String> x:s.locks.entrySet())locks.put(String.valueOf(x.getKey()),x.getValue());j.put("locks",locks);j.put("dead",new JSONArray(s.dead));
    }catch(JSONException e){throw new IllegalStateException(e);}return j;}
    static GameState decode(JSONObject j)throws JSONException{
        GameState s=new GameState();JSONArray rr=j.getJSONArray("roles");if(rr.length()<5||rr.length()>20)throw new JSONException("人数错误");s.roles=new String[rr.length()];for(int i=0;i<rr.length();i++)s.roles[i]=rr.getString(i);
        s.id=j.getString("id");s.boardName=j.getString("boardName");s.wolfCount=j.getInt("wolfCount");s.day=Math.max(1,j.optInt("day",1));s.speaker=Math.max(1,Math.min(s.players(),j.optInt("speaker",1)));s.phase=j.optString("phase","白天发言");s.draft=j.optString("draft","");
        int ownSeat=j.optInt("mySeat",0);s.mySeat=ownSeat>=1&&ownSeat<=s.players()?ownSeat:0;s.myRole=j.optString("myRole","");
        s.screenLocked=j.optBoolean("screenLocked",false)&&s.mySeat>0&&!s.myRole.isEmpty();
        s.screenEvidence=j.optString("screenEvidence","");s.screenRecognizedAt=Math.max(0,j.optLong("screenRecognizedAt",0));s.screenBoardId=j.optString("screenBoardId","");
        int autoSeat=j.optInt("screenAutoLockSeat",0);s.screenAutoLockSeat=autoSeat>=1&&autoSeat<=s.players()?autoSeat:0;s.screenAutoLockRole=j.optString("screenAutoLockRole","");
        JSONArray ss=j.optJSONArray("speeches");if(ss!=null)for(int i=0;i<ss.length();i++){JSONObject x=ss.getJSONObject(i);GameState.Speech a=new GameState.Speech();a.id=x.getString("id");a.day=x.getInt("day");a.speaker=x.getInt("speaker");a.text=x.getString("text");a.timestamp=x.getLong("timestamp");s.speeches.add(a);}
        JSONArray es=j.optJSONArray("events");if(es!=null)for(int i=0;i<es.length();i++){JSONObject x=es.getJSONObject(i);GameState.Event a=new GameState.Event();a.type=x.optString("type");a.detail=x.optString("detail");a.day=x.optInt("day",1);a.actor=x.optInt("actor");a.target=x.optInt("target");a.timestamp=x.optLong("timestamp");s.events.add(a);}
        JSONObject ll=j.optJSONObject("locks");if(ll!=null){Iterator<String> keys=ll.keys();while(keys.hasNext()){String k=keys.next();int seat=Integer.parseInt(k);if(seat>0&&seat<=s.players())s.locks.put(seat,ll.getString(k));}}
        JSONArray dd=j.optJSONArray("dead");if(dd!=null)for(int i=0;i<dd.length();i++)s.dead.add(dd.getInt(i));return s;
    }
    private static byte[] bytes(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}
    private static String read(File f)throws IOException{try(FileInputStream in=new AtomicFile(f).openRead()){return new String(bytes(in),StandardCharsets.UTF_8);}}
    private static void write(File f,String text){AtomicFile a=new AtomicFile(f);FileOutputStream out=null;try{out=a.startWrite();out.write(text.getBytes(StandardCharsets.UTF_8));a.finishWrite(out);}catch(IOException e){if(out!=null)a.failWrite(out);throw new IllegalStateException("本地保存失败，请检查存储空间",e);}}
}
