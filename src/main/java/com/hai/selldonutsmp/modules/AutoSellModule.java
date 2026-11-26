package com.hai.selldonutsmp.modules;

import com.hai.selldonutsmp.SellDonutSMPAddon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AutoSellModule extends Module {
    public enum Mode {
        CO_DINH("Setting 1 - Tick co dinh"),
        NGAU_NHIEN("Setting 2 - 4 chu ky ngau nhien");

        private final String title;

        Mode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private enum State { IDLE, WAIT_GUI, PUSH, SETTLE, AFTER_CONFIRM }

    private static final Random RNG = new Random();
    private static final int MAX_ROUNDS = 10;   // so lan bam xac nhan toi da moi dot
    private static final int MAX_PASSES = 3;    // so luot thu day lai neu server chua nhan

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCycles = settings.createGroup("Setting 2 - 4 chu ky");
    private final SettingGroup sgSell = settings.createGroup("Cho /sell");
    private final SettingGroup sgHuman = settings.createGroup("Chong flag");

    // ---------- Chung ----------
    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("chon-item")
        .description("Chi nhung item nay moi duoc day len /sell (de khong ban nham do khac).")
        .defaultValue(new ArrayList<>())
        .build()
    );

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("che-do")
        .description("Setting 1: tick co dinh. Setting 2: 4 chu ky tick rieng, lap lai lien tuc.")
        .defaultValue(Mode.CO_DINH)
        .build()
    );

    private final Setting<Integer> fixedTicks = sgGeneral.add(new IntSetting.Builder()
        .name("setting-1-tick-day-do")
        .description("Cu bao nhieu tick thi day TOAN BO item da chon len /sell (20 tick = 1 giay).")
        .defaultValue(200)
        .min(1)
        .sliderMax(1200)
        .build()
    );

    private final Setting<Integer> minStacks = sgGeneral.add(new IntSetting.Builder()
        .name("toi-thieu-o-item")
        .description("Trong tui do phai co it nhat bay nhieu o item da chon thi moi mo /sell.")
        .defaultValue(1)
        .min(1)
        .sliderMax(36)
        .build()
    );

    // ---------- Setting 2: 4 chu ky ----------
    private final Setting<Integer> cycle1 = cycleSetting("chu-ky-1-tick");
    private final Setting<Integer> cycle2 = cycleSetting("chu-ky-2-tick");
    private final Setting<Integer> cycle3 = cycleSetting("chu-ky-3-tick");
    private final Setting<Integer> cycle4 = cycleSetting("chu-ky-4-tick");

    private final Setting<Boolean> reroll = sgCycles.add(new BoolSetting.Builder()
        .name("random-lai-4-chu-ky")
        .description("Bat len de random lai 4 chu ky tu 10 den 100 tick (tu tat sau khi random).")
        .defaultValue(false)
        .build()
    );

    // ---------- Cho /sell ----------
    private final Setting<String> sellCommand = sgSell.add(new StringSetting.Builder()
        .name("lenh-sell")
        .description("Lenh mo giao dien ban do.")
        .defaultValue("/sell")
        .build()
    );

    private final Setting<Integer> topSlots = sgSell.add(new IntSetting.Builder()
        .name("so-o-tren-dung-duoc")
        .description("So o phia tren cua /sell dung duoc (DonutSMP: 45, hang duoi khong tinh).")
        .defaultValue(45)
        .min(1)
        .sliderMax(54)
        .build()
    );

    private final Setting<Integer> confirmSlot = sgSell.add(new IntSetting.Builder()
        .name("o-xac-nhan")
        .description("O de bam xac nhan ban (tinh tu 1). DonutSMP: o 54 la kinh xanh lime.")
        .defaultValue(54)
        .min(1)
        .sliderMax(54)
        .build()
    );

    private final Setting<Boolean> confirmWhenFull = sgSell.add(new BoolSetting.Builder()
        .name("bam-xac-nhan-khi-day")
        .description("Khi day het cac o phia tren thi tu bam o xac nhan.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> confirmWhenDone = sgSell.add(new BoolSetting.Builder()
        .name("bam-xac-nhan-khi-het-do")
        .description("Khi da day het item da chon (chua day o) cung tu bam o xac nhan.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> checkLime = sgSell.add(new BoolSetting.Builder()
        .name("chi-bam-neu-la-kinh-lime")
        .description("Chi bam o xac nhan neu dung la kinh xanh lime (tranh bam nham).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> stacksPerTick = sgSell.add(new IntSetting.Builder()
        .name("so-o-day-moi-tick")
        .description("Moi tick day toi da bao nhieu o. 64 = day HET trong mot tick (giong store cua Wurst).")
        .defaultValue(64)
        .min(1)
        .sliderMax(64)
        .build()
    );

    private final Setting<Integer> settleTicks = sgSell.add(new IntSetting.Builder()
        .name("cho-server-dong-bo-tick")
        .description("Sau khi day xong cho bao nhieu tick de server cap nhat roi moi kiem tra lai.")
        .defaultValue(12)
        .min(2)
        .sliderMax(60)
        .build()
    );

    private final Setting<Integer> waitAfterConfirm = sgSell.add(new IntSetting.Builder()
        .name("cho-sau-khi-bam-xac-nhan-tick")
        .description("Cho bao nhieu tick sau khi bam xac nhan roi moi lam tiep.")
        .defaultValue(15)
        .min(1)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> waitGuiSeconds = sgSell.add(new IntSetting.Builder()
        .name("cho-mo-gui-toi-da-giay")
        .description("Gui lenh /sell roi cho toi da bao nhieu giay de giao dien mo ra.")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> closeAfter = sgSell.add(new BoolSetting.Builder()
        .name("dong-gui-sau-khi-ban")
        .description("Ban xong thi tu dong giao dien /sell.")
        .defaultValue(true)
        .build()
    );

    // ---------- Chong flag ----------
    private final Setting<Integer> minDelay = sgHuman.add(new IntSetting.Builder()
        .name("tre-thao-tac-toi-thieu-tick")
        .description("Truoc moi dot day do se cho ngau nhien tu muc nay...")
        .defaultValue(2)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> maxDelay = sgHuman.add(new IntSetting.Builder()
        .name("tre-thao-tac-toi-da-tick")
        .description("...den muc nay (tick).")
        .defaultValue(5)
        .min(1)
        .sliderMax(40)
        .build()
    );

    private final Setting<Boolean> debug = sgHuman.add(new BoolSetting.Builder()
        .name("go-loi")
        .description("In ra cac buoc AutoSell dang lam.")
        .defaultValue(false)
        .build()
    );

    private State state = State.IDLE;
    private int wait = 100;      // dem nguoc den lan ban ke tiep
    private int timer = 0;       // dem nguoc giua cac buoc
    private int cycleIdx = 0;
    private int pushed = 0;
    private int rounds = 0;
    private int pass = 1;
    private boolean pickPlace = false;
    private boolean warnedEmpty = false;
    private Object lastWorld = null;
    private int stuckTicks = 0;
    private State lastState = State.IDLE;
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();

    public AutoSellModule() {
        super(SellDonutSMPAddon.CATEGORY, "AutoSell", "Tu dong /sell: day het item da chon len giao dien ban theo chu ky.");
    }

    private Setting<Integer> cycleSetting(String name) {
        return sgCycles.add(new IntSetting.Builder()
            .name(name)
            .description("So tick cho chu ky nay (20 tick = 1 giay). Mac dinh random tu 10 den 100 luc moi cai dat.")
            .defaultValue(10 + RNG.nextInt(91))
            .min(1)
            .sliderMax(1200)
            .build()
        );
    }

    /** AutoPay / SellAxe dung ham nay de biet AutoSell dang ban do. */
    public boolean isBusy() {
        return state != State.IDLE;
    }

    @Override
    public void onActivate() {
        state = State.IDLE;
        rounds = 0;
        warnedEmpty = false;
        lastWorld = mc.world;
        stuckTicks = 0;
        boolean justJoined = mc.player == null || mc.player.age < 40;
        wait = justJoined ? 100 : nextInterval(); // vao lai server: tat 5 giay roi chay lai
    }

    @Override
    public void onDeactivate() {
        if (state != State.IDLE && mc.player != null && mc.currentScreen instanceof GenericContainerScreen) {
            mc.player.closeHandledScreen();
        }
        state = State.IDLE;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        lastWorld = mc.world;
        abortSession(100); // vao lai server: huy dot ban dang do, 5 giay sau chay lai
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        state = State.IDLE;
        rounds = 0;
        queue.clear();
        lastWorld = null;
    }

    // Canh bao restart region: dung AutoSell. Neu SellAxe dang bat thi cho SellAxe restart xong (+5 giay), neu khong thi cho 30 giay.
    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        String m = event.getMessage().getString().toLowerCase();
        if (!m.contains("server are updating") && !m.contains("server is updating")) return;

        SellAxeModule sa = Modules.get().get(SellAxeModule.class);
        abortSession(sa != null && sa.isActive() ? 100 : 600);
        info("Restart region, tam dung AutoSell.");
    }

    // Huy dot ban dang do (dong giao dien neu dang mo) va hen gio chay lai
    private void abortSession(int nextWait) {
        if (state != State.IDLE && mc.player != null && mc.currentScreen instanceof GenericContainerScreen) {
            mc.player.closeHandledScreen();
        }
        state = State.IDLE;
        rounds = 0;
        pushed = 0;
        queue.clear();
        wait = nextWait;
    }

    // ---------- Lich ----------

    private int nextInterval() {
        if (mode.get() == Mode.CO_DINH) return Math.max(1, fixedTicks.get());
        int v = switch (cycleIdx) {
            case 0 -> cycle1.get();
            case 1 -> cycle2.get();
            case 2 -> cycle3.get();
            default -> cycle4.get();
        };
        cycleIdx = (cycleIdx + 1) % 4;
        return Math.max(1, v);
    }

    private int humanDelay() {
        int lo = Math.max(1, minDelay.get());
        int hi = Math.max(lo, maxDelay.get());
        return lo + RNG.nextInt(hi - lo + 1);
    }

    private void rerollCycles() {
        cycle1.set(10 + RNG.nextInt(91));
        cycle2.set(10 + RNG.nextInt(91));
        cycle3.set(10 + RNG.nextInt(91));
        cycle4.set(10 + RNG.nextInt(91));
        info("Da random lai 4 chu ky: " + cycle1.get() + ", " + cycle2.get() + ", " + cycle3.get() + ", " + cycle4.get() + " tick.");
    }

    // ---------- Tien trinh ----------

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (reroll.get()) {
            rerollCycles();
            reroll.set(false);
        }

        // Doi world ma khong co su kien join (bi chuyen region) -> huy dot dang do, 5 giay sau chay lai
        if (mc.world != lastWorld) {
            boolean first = lastWorld == null;
            lastWorld = mc.world;
            if (!first) abortSession(100);
        }

        // Chot an toan: ket o mot buoc qua 30 giay thi dat lai
        if (state != State.IDLE && state == lastState) {
            if (++stuckTicks > 600) {
                warning("AutoSell bi ket qua lau, dat lai.");
                stuckTicks = 0;
                abortSession(100);
            }
        } else {
            stuckTicks = 0;
        }
        lastState = state;

        switch (state) {
            case IDLE -> idle();
            case WAIT_GUI -> waitGui();
            case PUSH -> {
                if (--timer <= 0) push();
            }
            case SETTLE -> {
                if (--timer <= 0) settle();
            }
            case AFTER_CONFIRM -> {
                if (--timer <= 0) afterConfirm();
            }
        }
    }

    private void idle() {
        if (--wait > 0) return;

        if (mc.player.isDead()) { // dang chet thi doi
            wait = 20;
            return;
        }
        SellAxeModule axe = Modules.get().get(SellAxeModule.class);
        if (axe != null && axe.isActive() && axe.isRestarting()) { // SellAxe dang cho restart region -> doi theo
            wait = 100;
            return;
        }

        AutoPayModule pay = Modules.get().get(AutoPayModule.class);
        if (pay != null && pay.isBusy()) {
            wait = 20; // dang pay thi doi
            return;
        }
        SellAxeModule sx = Modules.get().get(SellAxeModule.class);
        if (sx != null && sx.isBuyingFood()) {
            wait = 20; // dang mua do an thi doi
            return;
        }
        if (mc.currentScreen != null) {
            wait = 20; // dang mo giao dien khac
            return;
        }

        if (items.get().isEmpty()) {
            if (!warnedEmpty) {
                warning("Chua chon item nao trong chon-item, AutoSell khong ban gi ca.");
                warnedEmpty = true;
            }
            wait = nextInterval();
            return;
        }
        warnedEmpty = false;

        if (countSelected() < minStacks.get()) {
            wait = nextInterval();
            return;
        }

        String cmd = sellCommand.get().trim();
        if (!cmd.startsWith("/")) cmd = "/" + cmd;
        ChatUtils.sendPlayerMsg(cmd);
        if (debug.get()) info("Gui lenh " + cmd);

        if (rounds == 0) pushed = 0;
        pass = 1;
        pickPlace = false;
        state = State.WAIT_GUI;
        timer = waitGuiSeconds.get() * 20;
    }

    private void waitGui() {
        GenericContainerScreenHandler h = sellHandler();
        if (h != null) {
            startPass(h);
            return;
        }
        if (--timer <= 0) {
            warning("Khong mo duoc giao dien /sell, bo qua luot nay.");
            state = State.IDLE;
            wait = nextInterval();
        }
    }

    // Lap danh sach TOAN BO o item da chon trong tui do, roi day lan luot tung o
    private void startPass(GenericContainerScreenHandler h) {
        queue.clear();
        for (int i = topSize(h); i < h.slots.size(); i++) {
            if (isSelected(h.getSlot(i).getStack())) queue.add(i);
        }
        if (debug.get()) info("Luot " + pass + ": co " + queue.size() + " o can day (" + (pickPlace ? "nhat-tha" : "shift-click") + ").");
        state = State.PUSH;
        timer = humanDelay();
    }

    // Day TOAN BO hang doi len /sell trong mot lan (toi da so-o-day-moi-tick o moi tick).
    // Moi cu bam duoc tinh tren trang thai da cap nhat cua client nen cac cu bam lien tiep van khop nhau.
    private void push() {
        GenericContainerScreenHandler h = sellHandler();
        if (h == null) {
            finish();
            return;
        }

        int batch = Math.max(1, stacksPerTick.get());
        int done = 0;

        while (done < batch) {
            if (firstEmptyTop(h) < 0) { // da day o phia tren
                toSettle();
                return;
            }

            Integer src = queue.poll();
            if (src == null) { // het hang doi
                toSettle();
                return;
            }
            if (!isSelected(h.getSlot(src).getStack())) continue; // o nay da het/khac -> sang o ke

            if (pickPlace) { // cach du phong: nhat roi tha vao o trong dau tien
                int dst = firstEmptyTop(h);
                click(h, src, SlotActionType.PICKUP);
                click(h, dst, SlotActionType.PICKUP);
            } else {
                click(h, src, SlotActionType.QUICK_MOVE);
            }
            pushed++;
            done++;
        }

        timer = 1; // con hang doi -> tick sau day tiep
    }

    private void toSettle() {
        state = State.SETTLE;
        timer = settleTicks.get();
    }

    // Cho server dong bo roi kiem tra: day chua? con item chua day khong?
    private void settle() {
        GenericContainerScreenHandler h = sellHandler();
        if (h == null) {
            finish();
            return;
        }

        boolean full = firstEmptyTop(h) < 0;
        boolean remain = findSelected(h) >= 0;

        if (full) {
            confirmOrFinish(h, confirmWhenFull.get());
            return;
        }

        if (remain) {
            if (pass < MAX_PASSES) { // server chua nhan het -> thu lai bang cach nhat-tha
                pass++;
                pickPlace = true;
                startPass(h);
                return;
            }
            warning("Con item chua day len duoc (server khong nhan). Bat go-loi de xem chi tiet.");
        }

        confirmOrFinish(h, pushed > 0 && confirmWhenDone.get());
    }

    private void confirmOrFinish(GenericContainerScreenHandler h, boolean doConfirm) {
        if (!doConfirm) {
            finish();
            return;
        }

        int idx = confirmSlot.get() - 1;
        if (idx < 0 || idx >= topSize(h)) {
            warning("O xac nhan khong hop le.");
            finish();
            return;
        }
        if (checkLime.get() && !h.getSlot(idx).getStack().isOf(Items.LIME_STAINED_GLASS_PANE)) {
            warning("O so " + confirmSlot.get() + " khong phai kinh xanh lime, khong bam.");
            finish();
            return;
        }

        click(h, idx, SlotActionType.PICKUP);
        if (debug.get()) info("Bam o xac nhan " + confirmSlot.get() + ".");
        rounds++;
        state = State.AFTER_CONFIRM;
        timer = waitAfterConfirm.get();
    }

    private void afterConfirm() {
        GenericContainerScreenHandler h = sellHandler();

        if (h == null) { // giao dien tu dong sau khi ban
            state = State.IDLE;
            if (countSelected() > 0 && rounds < MAX_ROUNDS) {
                wait = 20; // con do -> mo lai /sell sau 1 giay
            } else {
                summary();
                wait = nextInterval();
                rounds = 0;
            }
            return;
        }

        if (findSelected(h) >= 0 && firstEmptyTop(h) >= 0 && rounds < MAX_ROUNDS) {
            pass = 1;
            pickPlace = false;
            startPass(h); // con item -> day tiep
        } else {
            finish();
        }
    }

    private void summary() {
        if (pushed > 0) info("AutoSell: da day " + pushed + " o item len /sell.");
    }

    private void finish() {
        summary();
        if (closeAfter.get() && mc.player != null && mc.currentScreen instanceof GenericContainerScreen) {
            mc.player.closeHandledScreen();
        }
        state = State.IDLE;
        wait = nextInterval();
        rounds = 0;
        queue.clear();
    }

    // ---------- Tien ich ----------

    private GenericContainerScreenHandler sellHandler() {
        if (mc.currentScreen instanceof GenericContainerScreen s && s.getScreenHandler().getRows() == 6) {
            return s.getScreenHandler();
        }
        return null;
    }

    private int topSize(GenericContainerScreenHandler h) {
        return h.getRows() * 9;
    }

    private boolean isSelected(ItemStack s) {
        return !s.isEmpty() && items.get().contains(s.getItem());
    }

    private int firstEmptyTop(GenericContainerScreenHandler h) {
        int limit = Math.min(topSlots.get(), topSize(h));
        for (int i = 0; i < limit; i++) {
            if (h.getSlot(i).getStack().isEmpty()) return i;
        }
        return -1;
    }

    private int findSelected(GenericContainerScreenHandler h) {
        for (int i = topSize(h); i < h.slots.size(); i++) {
            if (isSelected(h.getSlot(i).getStack())) return i;
        }
        return -1;
    }

    // Dem so o item da chon trong tui do (o 9-35 la tui, 36-44 la hotbar)
    private int countSelected() {
        int n = 0;
        for (int i = 9; i <= 44; i++) {
            if (isSelected(mc.player.playerScreenHandler.getSlot(i).getStack())) n++;
        }
        return n;
    }

    private void click(GenericContainerScreenHandler h, int slot, SlotActionType type) {
        mc.interactionManager.clickSlot(h.syncId, slot, 0, type, mc.player);
    }
}
