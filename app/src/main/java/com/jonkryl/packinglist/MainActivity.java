package com.jonkryl.packinglist;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.*;
import com.jonkryl.packinglist.ads.BannerController;
import com.jonkryl.packinglist.domain.*;
import com.jonkryl.packinglist.domain.PackingModels.*;
import java.time.LocalDate;
import java.util.*;

/** All product state is persisted by the repository before a new UI state is shown. */
public final class MainActivity extends ComponentActivity {
    private static final int INK=0xff193a38, TEAL=0xff176b5a, MUTED=0xff526b67, PAPER=0xfff4f6f1;
    private PackingRepository repository;
    private PackingState packingState;
    private long tripId=-1;
    private boolean remaining=false;
    private Grouping grouping=Grouping.NONE;
    private RecyclerView list;
    private Rows adapter;
    private LinearLayout root, undoBar;
    private TextView title;
    private Button action, back;
    private BannerController banner;
    private PackingRepository.UndoToken undo;
    private boolean english;
    private OnBackPressedCallback tripBack;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        english=!getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru");
        packingState=new ViewModelProvider(this,new PackingState.Factory(getApplication())).get(PackingState.class);
        repository=packingState.repository;
        if(state!=null){ tripId=state.getLong("trip",-1); remaining=state.getBoolean("remaining"); grouping=Grouping.valueOf(state.getString("group","NONE")); }
        else tripId=getPreferences(0).getLong("trip",-1);
        tripBack=new OnBackPressedCallback(tripId>=0){
            @Override public void handleOnBackPressed(){goHome();}
        };
        getOnBackPressedDispatcher().addCallback(this,tripBack);
        root=column(); root.setBackgroundColor(PAPER);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{androidx.core.graphics.Insets s=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(s.left,s.top,s.right,s.bottom);return insets;});
        LinearLayout bar=row(); bar.setPadding(dp(12),dp(6),dp(12),dp(6));
        back=button("‹",v->goHome()); back.setContentDescription(t("Все поездки","All trips")); bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        title=text(t("Собрано","Packed"),22,true); bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button menu=button("⋮",this::appMenu); menu.setContentDescription(t("Меню","Menu"));bar.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(bar);
        list=new RecyclerView(this); list.setId(R.id.main_list);list.setLayoutManager(new LinearLayoutManager(this));list.setItemAnimator(null);
        adapter=new Rows(); list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        undoBar=row();undoBar.setPadding(dp(12),0,dp(12),0);undoBar.setBackgroundColor(0xffdcebe3);root.addView(undoBar);undoBar.setVisibility(View.GONE);
        action=button("",v->{if(tripId<0)tripEditor(null);else itemEditor(null);}); action.setId(R.id.add_button);action.setTextColor(Color.WHITE);action.setBackground(round(TEAL,16));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.setMargins(dp(16),dp(8),dp(16),dp(12));root.addView(action,ap);
        FrameLayout ad=new FrameLayout(this);ad.setId(R.id.ad_container);root.addView(ad,new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);banner=new BannerController(this);banner.attach(ad);refresh(false);
        if(packingState.undo!=null)offerUndo(packingState.undo);
    }
    @Override protected void onStart(){super.onStart();if(banner!=null)banner.onStart();}
    @Override protected void onStop(){if(banner!=null)banner.onStop();super.onStop();}
    @Override protected void onDestroy(){if(banner!=null)banner.destroy();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putLong("trip",tripId);b.putBoolean("remaining",remaining);b.putString("group",grouping.name());}
    private String t(String ru,String en){return english?en:ru;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(INK);if(bold)v.setTypeface(null,Typeface.BOLD);v.setIncludeFontPadding(true);return v;}
    private Button button(String s,View.OnClickListener click){Button b=new Button(this);b.setText(s);b.setTextSize(16);b.setAllCaps(false);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setTextColor(TEAL);b.setOnClickListener(click);return b;}
    private void goHome(){tripId=-1;remaining=false;refresh(false);}
    private void openTrip(long id){tripId=id;remaining=false;grouping=Grouping.NONE;refresh(false);}
    private void refresh(boolean keepScroll){
        LinearLayoutManager layout=(LinearLayoutManager)list.getLayoutManager();
        int oldFirst=keepScroll?layout.findFirstVisibleItemPosition():-1; int offset=0;ArrayList<Long> oldIds=new ArrayList<>();
        if(oldFirst>=0){View anchor=layout.findViewByPosition(oldFirst);if(anchor!=null)offset=anchor.getTop()-list.getPaddingTop();for(Entry e:adapter.data)oldIds.add(e.id);}
        Trip trip=tripId>=0?repository.getTrip(tripId):null;if(tripId>=0&&trip==null)tripId=-1;
        tripBack.setEnabled(tripId>=0);
        getPreferences(0).edit().putLong("trip",tripId).apply();
        title.setText(t("Собрано","Packed")); back.setVisibility(tripId>=0?View.VISIBLE:View.GONE);
        action.setText(tripId<0?t("＋ Новая поездка","＋ New trip"):t("＋ Добавить вещь","＋ Add item"));
        adapter.data.clear();
        if(tripId<0){adapter.data.add(new Entry(-1,"intro",null));for(Trip v:repository.listTrips())adapter.data.add(new Entry(v.id,"trip",v));if(repository.listTrips().isEmpty())adapter.data.add(new Entry(-2,"empty",t("Начните с поездки. Шаблон можно полностью изменить.","Start with a trip. Every template is fully editable.")));}
        else {adapter.data.add(new Entry(-1,"hero",trip));adapter.data.add(new Entry(-2,"filters",trip));
            for(ItemGroup g:PackingLogic.groups(trip,remaining,grouping,english)){if(grouping!=Grouping.NONE)adapter.data.add(new Entry(-100000-(g.ownerId==null?0:g.ownerId),"group",g.label));for(Item i:g.items)adapter.data.add(new Entry(i.id,"item",i));}
            if(PackingLogic.visibleItems(trip,remaining,grouping).isEmpty())adapter.data.add(new Entry(-3,"empty",remaining?t("Всё собрано. Можно выдохнуть.","Everything is packed. Take a breath."):t("Добавьте первую вещь.","Add your first item.")));
        }
        adapter.notifyDataSetChanged();
        int target=-1;if(oldFirst>=0){for(int j=oldFirst;j<oldIds.size()&&target<0;j++)target=rowIndex(oldIds.get(j));for(int j=oldFirst-1;j>=0&&target<0;j--)target=rowIndex(oldIds.get(j));}
        if(target>=0)layout.scrollToPositionWithOffset(target,offset);else list.scrollToPosition(0);
    }
    private int rowIndex(long id){for(int i=0;i<adapter.data.size();i++)if(adapter.data.get(i).id==id)return i;return -1;}
    private void safe(Runnable operation){try{operation.run();}catch(RuntimeException e){refresh(true);new AlertDialog.Builder(this).setTitle(t("Изменение не сохранено","Change was not saved")).setMessage(e instanceof IllegalArgumentException?e.getMessage():t("Не удалось сохранить данные. Освободите место и повторите. Предыдущие данные сохранены.","Could not save. Free some storage and try again. Previous data is preserved.")).setPositiveButton("OK",null).show();}}
    private void offerUndo(PackingRepository.UndoToken token){undo=token;packingState.undo=token;undoBar.removeAllViews();TextView label=text(t("Изменение сохранено","Change saved"),14,false);undoBar.addView(label,new LinearLayout.LayoutParams(0,-2,1));Button b=button(t("Отменить","Undo"),v->safe(()->{if(repository.undo(undo)){undo=null;packingState.undo=null;undoBar.setVisibility(View.GONE);refresh(true);}}));b.setId(R.id.undo_button);undoBar.addView(b);undoBar.setVisibility(View.VISIBLE);}
    private void appMenu(View anchor){PopupMenu p=new PopupMenu(this,anchor);p.getMenu().add(t("Приватность рекламы","Ad privacy")).setOnMenuItemClickListener(m->{banner.showPrivacyChoice();return true;});p.getMenu().add(t("Политика конфиденциальности","Privacy policy")).setOnMenuItemClickListener(m->{banner.openPrivacyPolicy();return true;});p.show();}
    private void tripMenu(Trip trip,View anchor){PopupMenu p=new PopupMenu(this,anchor);p.getMenu().add(t("Изменить поездку","Edit trip")).setOnMenuItemClickListener(m->{tripEditor(trip);return true;});p.getMenu().add(t("Участники и сумки","People and bags")).setOnMenuItemClickListener(m->{manageOwners(trip);return true;});p.getMenu().add(t("Копировать поездку","Copy trip")).setOnMenuItemClickListener(m->{copyDialog(trip);return true;});p.getMenu().add(t("Поделиться списком","Share list")).setOnMenuItemClickListener(m->{shareTrip(trip);return true;});p.getMenu().add(t("Удалить поездку","Delete trip")).setOnMenuItemClickListener(m->{new AlertDialog.Builder(this).setTitle(t("Удалить поездку?","Delete trip?")).setMessage(trip.title).setNegativeButton(t("Оставить","Keep"),null).setPositiveButton(t("Удалить","Delete"),(d,w)->safe(()->{offerUndo(repository.deleteTrip(trip.id));refresh(true);})).show();return true;});p.show();}
    public void shareTrip(Trip trip){Intent send=new Intent(Intent.ACTION_SEND);send.setType("text/plain");send.putExtra(Intent.EXTRA_SUBJECT,trip.title);send.putExtra(Intent.EXTRA_TEXT,repository.exportTrip(trip.id,english));startActivity(Intent.createChooser(send,t("Поделиться списком","Share packing list")));}
    private EditText field(LinearLayout box,String label,String value,int id,int type){TextView l=text(label,14,true);box.addView(l);EditText e=new EditText(this);e.setId(id);e.setText(value);e.setTextSize(18);e.setInputType(type);e.setMinHeight(dp(48));e.setTextColor(INK);box.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
    private LinearLayout form(){LinearLayout b=column();b.setPadding(dp(20),dp(8),dp(20),dp(12));return b;}
    private AlertDialog dialog(String heading,LinearLayout box){ScrollView scroll=new ScrollView(this);scroll.addView(box);return new AlertDialog.Builder(this).setTitle(heading).setView(scroll).setNegativeButton(t("Отмена","Cancel"),null).setPositiveButton(t("Сохранить","Save"),null).create();}
    private void tripEditor(Trip trip){LinearLayout b=form();EditText name=field(b,t("Название поездки","Trip name"),trip==null?"":trip.title,R.id.trip_name,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText start=field(b,t("Начало · ГГГГ-ММ-ДД (необязательно)","Start · YYYY-MM-DD (optional)"),trip==null?"":trip.startDate,R.id.start_date,InputType.TYPE_CLASS_DATETIME);EditText end=field(b,t("Конец · ГГГГ-ММ-ДД (необязательно)","End · YYYY-MM-DD (optional)"),trip==null?"":trip.endDate,R.id.end_date,InputType.TYPE_CLASS_DATETIME);
        Spinner templates=new Spinner(this);if(trip==null){b.addView(text(t("Стартовый список","Start with"),14,true));templates.setId(R.id.template_spinner);templates.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{t("Пустой список","Empty list"),t("Выходные","Weekend"),t("Рабочая поездка","Business trip"),t("С детьми","With kids")}));b.addView(templates,new LinearLayout.LayoutParams(-1,dp(56)));b.addView(text(t("Все вещи, участники и сумки можно менять.","All items, people and bags can be changed."),14,false));}
        AlertDialog d=dialog(trip==null?t("Новая поездка","New trip"):t("Изменить поездку","Edit trip"),b);d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->safe(()->{String n=name.getText().toString().trim(),s=start.getText().toString().trim(),e=end.getText().toString().trim();if(n.isEmpty()){name.setError(t("Введите название","Enter a name"));return;}if(!validDate(s)||!validDate(e)||(!s.isEmpty()&&!e.isEmpty()&&s.compareTo(e)>0)){end.setError(t("Проверьте даты","Check dates"));return;}if(trip==null){tripId=repository.createTrip(n,s,e,Template.values()[templates.getSelectedItemPosition()],english);}else offerUndo(repository.updateTrip(trip.id,n,s,e));d.dismiss();refresh(false);})));d.show();}
    private boolean validDate(String s){if(s.isEmpty())return true;try{LocalDate.parse(s);return s.matches("\\d{4}-\\d{2}-\\d{2}");}catch(Exception e){return false;}}
    private void itemEditor(Item item){Trip trip=repository.getTrip(tripId);if(trip==null)return;LinearLayout b=form();EditText name=field(b,t("Название вещи","Item name"),item==null?"":item.name,R.id.item_name,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);EditText quantity=field(b,t("Количество","Quantity"),item==null?"1":String.valueOf(item.quantity),R.id.item_quantity,InputType.TYPE_CLASS_NUMBER);
        ArrayList<Long> personIds=new ArrayList<>(),bagIds=new ArrayList<>();ArrayList<String> people=new ArrayList<>(),bags=new ArrayList<>();personIds.add(null);people.add(t("Общее / без участника","Shared / no person"));bagIds.add(null);bags.add(t("Без сумки","No bag"));for(Person p:trip.people){personIds.add(p.id);people.add(p.name);}for(Bag a:trip.bags){bagIds.add(a.id);bags.add(a.name);}
        Spinner person=select(b,t("Участник","Person"),people,R.id.person_spinner),bag=select(b,t("Сумка","Bag"),bags,R.id.bag_spinner);if(item!=null){person.setSelection(Math.max(0,personIds.indexOf(item.personId)));bag.setSelection(Math.max(0,bagIds.indexOf(item.bagId)));}
        b.addView(button(t("＋ Добавить участника","＋ Add person"),v->addOwnerFromItem(trip,true,personIds,people,person))); b.addView(button(t("＋ Добавить сумку","＋ Add bag"),v->addOwnerFromItem(trip,false,bagIds,bags,bag)));
        AlertDialog d=dialog(item==null?t("Добавить вещь","Add item"):t("Изменить вещь","Edit item"),b);d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->safe(()->{String n=name.getText().toString().trim();int q;try{q=Integer.parseInt(quantity.getText().toString());}catch(Exception e){q=0;}if(n.isEmpty()){name.setError(t("Введите название","Enter a name"));return;}if(q<1||q>9999){quantity.setError(t("От 1 до 9999","Use 1 to 9999"));return;}if(item==null)repository.addItem(trip.id,n,q,personIds.get(person.getSelectedItemPosition()),bagIds.get(bag.getSelectedItemPosition()));else offerUndo(repository.updateItem(trip.id,item.id,n,q,personIds.get(person.getSelectedItemPosition()),bagIds.get(bag.getSelectedItemPosition())));d.dismiss();refresh(true);})));d.show();}
    private void addOwnerFromItem(Trip trip,boolean person,ArrayList<Long> ids,ArrayList<String> names,Spinner spinner){
        LinearLayout b=form();EditText name=field(b,t("Название","Name"),"",R.id.owner_name,InputType.TYPE_CLASS_TEXT);AlertDialog d=dialog(person?t("Добавить участника","Add person"):t("Добавить сумку","Add bag"),b);
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->safe(()->{String n=name.getText().toString().trim();if(n.isEmpty()){name.setError(t("Введите название","Enter a name"));return;}long id=person?repository.addPerson(trip.id,n):repository.addBag(trip.id,n);ids.add(id);names.add(n);spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));spinner.setSelection(ids.size()-1);d.dismiss();refresh(true);})));d.show();
    }
    private Spinner select(LinearLayout b,String label,List<String> options,int id){b.addView(text(label,14,true));Spinner s=new Spinner(this);s.setId(id);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));b.addView(s,new LinearLayout.LayoutParams(-1,dp(56)));return s;}
    private void itemMenu(Item item,View anchor){PopupMenu p=new PopupMenu(this,anchor);p.getMenu().add(t("Изменить вещь","Edit item")).setOnMenuItemClickListener(m->{itemEditor(item);return true;});p.getMenu().add(t("Удалить вещь","Delete item")).setOnMenuItemClickListener(m->{safe(()->{offerUndo(repository.deleteItem(tripId,item.id));refresh(true);});return true;});p.show();}
    private String copyName(String original){String suffix=t(" — копия"," — copy");int limit=Math.min(original.length(),120-suffix.length());if(limit>0&&limit<original.length()&&Character.isHighSurrogate(original.charAt(limit-1))&&Character.isLowSurrogate(original.charAt(limit)))limit--;return original.substring(0,limit)+suffix;}
    private void copyDialog(Trip trip){LinearLayout b=form();EditText name=field(b,t("Название копии","Copy name"),copyName(trip.title),R.id.copy_name,InputType.TYPE_CLASS_TEXT);CheckBox reset=new CheckBox(this);reset.setText(t("Сбросить отметки сборки","Reset packed marks"));reset.setTextSize(16);reset.setChecked(true);reset.setMinHeight(dp(48));reset.setId(R.id.reset_marks);b.addView(reset);b.addView(text(t("Люди, сумки и количество сохранятся.","People, bags and quantities will be copied."),14,false));AlertDialog d=dialog(t("Копировать поездку","Copy trip"),b);d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->safe(()->{if(name.getText().toString().trim().isEmpty()){name.setError(t("Введите название","Enter a name"));return;}long copy=repository.copyTrip(trip.id,name.getText().toString(),reset.isChecked());d.dismiss();openTrip(copy);})));d.show();}
    private void manageOwners(Trip trip){Trip fresh=repository.getTrip(trip.id);if(fresh==null)return;ArrayList<String> options=new ArrayList<>();options.add(t("＋ Добавить участника","＋ Add person"));options.add(t("＋ Добавить сумку","＋ Add bag"));for(Person p:fresh.people)options.add(t("Участник: ","Person: ")+p.name);for(Bag b:fresh.bags)options.add(t("Сумка: ","Bag: ")+b.name);new AlertDialog.Builder(this).setTitle(t("Участники и сумки","People and bags")).setItems(options.toArray(new String[0]),(d,i)->{if(i<2)ownerEditor(fresh,i==0,null,"");else if(i<2+fresh.people.size()){Person p=fresh.people.get(i-2);ownerActions(fresh,true,p.id,p.name);}else{Bag b=fresh.bags.get(i-2-fresh.people.size());ownerActions(fresh,false,b.id,b.name);}}).setNegativeButton(t("Готово","Done"),null).show();}
    private void ownerActions(Trip trip,boolean person,long id,String name){new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{t("Переименовать","Rename"),t("Удалить (вещи останутся)","Delete (keep items)")},(d,i)->{if(i==0)ownerEditor(trip,person,id,name);else safe(()->{offerUndo(person?repository.deletePerson(trip.id,id):repository.deleteBag(trip.id,id));refresh(true);});}).show();}
    private void ownerEditor(Trip trip,boolean person,Long id,String old){LinearLayout b=form();EditText n=field(b,t("Название","Name"),old,R.id.owner_name,InputType.TYPE_CLASS_TEXT);AlertDialog d=dialog(person?t("Участник","Person"):t("Сумка","Bag"),b);d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->safe(()->{String name=n.getText().toString().trim();if(name.isEmpty()){n.setError(t("Введите название","Enter a name"));return;}if(id==null){if(person)repository.addPerson(trip.id,name);else repository.addBag(trip.id,name);}else offerUndo(person?repository.updatePerson(trip.id,id,name):repository.updateBag(trip.id,id,name));d.dismiss();refresh(true);})));d.show();}
    public PackingRepository repositoryForTests(){return repository;}
    public long currentTripIdForTests(){return tripId;}
    private static final class Entry{final long id;final String type;final Object value;Entry(long i,String t,Object v){id=i;type=t;value=v;}}
    private final class Rows extends RecyclerView.Adapter<Rows.Holder>{
        final ArrayList<Entry> data=new ArrayList<>();Rows(){setHasStableIds(true);}
        @Override public int getItemCount(){return data.size();}@Override public long getItemId(int i){return data.get(i).id;}
        @Override public Holder onCreateViewHolder(ViewGroup p,int type){LinearLayout v=column();v.setPadding(dp(16),dp(8),dp(16),dp(8));v.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));return new Holder(v);}
        @Override public void onBindViewHolder(Holder h,int position){LinearLayout box=h.box;box.removeAllViews();box.setPadding(dp(16),dp(8),dp(16),dp(8));Entry e=data.get(position);Trip trip=tripId<0?null:repository.getTrip(tripId);
            if(e.type.equals("intro")){box.addView(text(t("Легче собраться.","Ready to go."),30,true));TextView sub=text(t("Кому. В какую сумку. Что осталось.","Whose item. Which bag. What is left."),16,false);sub.setTextColor(MUTED);box.addView(sub);}
            else if(e.type.equals("empty")){box.setPadding(dp(24),dp(28),dp(24),dp(28));box.addView(text((String)e.value,18,false));}
            else if(e.type.equals("group")){TextView v=text((String)e.value,18,true);v.setTextColor(TEAL);box.addView(v);}
            else if(e.type.equals("trip")||e.type.equals("hero")){Trip v=(Trip)e.value;LinearLayout card=column();card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(round(Color.WHITE,18));LinearLayout header=row();TextView n=text(v.title,e.type.equals("hero")?26:22,true);header.addView(n,new LinearLayout.LayoutParams(0,-2,1));Button more=button("⋮",b->tripMenu(v,b));more.setContentDescription(t("Действия с поездкой ","Trip actions ")+v.title);header.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));card.addView(header);
                if(!v.startDate.isEmpty()||!v.endDate.isEmpty())card.addView(text(v.startDate+(v.endDate.isEmpty()?"":" — "+v.endDate),14,false));int packed=0;for(Item i:v.items)if(i.packed)packed++;card.addView(text(t("Собрано ","Packed ")+packed+" / "+v.items.size(),16,true));ProgressBar progress=new ProgressBar(MainActivity.this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(Math.max(1,v.items.size()));progress.setProgress(packed);card.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
                if(e.type.equals("trip")){Button open=button(t("Открыть список →","Open list →"),b->openTrip(v.id));card.addView(open);}else{Button owners=button(t("Участники и сумки","People and bags"),b->manageOwners(v));owners.setId(R.id.owners_button);card.addView(owners);Button share=button(t("Поделиться списком","Share list"),b->shareTrip(v));share.setId(R.id.share_button);card.addView(share);}box.addView(card);}
            else if(e.type.equals("filters")){CheckBox filter=new CheckBox(MainActivity.this);filter.setId(R.id.remaining_filter);filter.setText(t("Осталось собрать","Remaining to pack"));filter.setTextSize(16);filter.setMinHeight(dp(48));filter.setChecked(remaining);filter.setOnCheckedChangeListener((b,c)->{remaining=c;refresh(true);});box.addView(filter);Button group=button(t("Группировка: ","Group: ")+(grouping==Grouping.NONE?t("по порядку","in order"):grouping==Grouping.PERSON?t("по участнику","by person"):t("по сумке","by bag")),v->new AlertDialog.Builder(MainActivity.this).setTitle(t("Группировка","Group items")).setSingleChoiceItems(new String[]{t("По порядку","In order"),t("По участнику","By person"),t("По сумке","By bag")},grouping.ordinal(),(d,i)->{grouping=Grouping.values()[i];d.dismiss();refresh(false);}).show());group.setId(R.id.group_button);box.addView(group);}
            else if(e.type.equals("item")){Item i=(Item)e.value;LinearLayout r=row();r.setPadding(dp(8),dp(6),dp(4),dp(6));r.setBackground(round(i.packed?0xffe5eee6:Color.WHITE,14));CheckBox mark=new CheckBox(MainActivity.this);mark.setChecked(i.packed);mark.setMinHeight(dp(48));mark.setContentDescription(t("Собрано: ","Packed: ")+i.name+" × "+i.quantity+" · "+(trip.personName(i.personId).isEmpty()?t("Общее","Shared"):trip.personName(i.personId))+" · "+(trip.bagName(i.bagId).isEmpty()?t("Без сумки","No bag"):trip.bagName(i.bagId)));mark.setTag("packed_"+i.id);mark.setOnCheckedChangeListener((b,c)->safe(()->{repository.setPacked(tripId,i.id,c);refresh(true);}));r.addView(mark,new LinearLayout.LayoutParams(dp(48),-2));LinearLayout detail=column();TextView n=text(i.name+" × "+i.quantity,18,true);detail.addView(n);String person=trip.personName(i.personId),bag=trip.bagName(i.bagId);TextView metadata=text((person.isEmpty()?t("Общее","Shared"):person)+" · "+(bag.isEmpty()?t("Без сумки","No bag"):bag),14,false);metadata.setTextColor(MUTED);detail.addView(metadata);detail.setOnClickListener(v->itemEditor(i));detail.setPadding(0,dp(4),0,dp(4));detail.setMinimumHeight(dp(48));r.addView(detail,new LinearLayout.LayoutParams(0,-2,1));Button more=button("⋮",v->itemMenu(i,v));more.setContentDescription(t("Действия с вещью ","Item actions ")+i.name);r.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));box.addView(r);}
        }
        final class Holder extends RecyclerView.ViewHolder{final LinearLayout box;Holder(LinearLayout v){super(v);box=v;}}
    }
}
