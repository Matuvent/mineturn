package com.matuvent.mineturn.client;

import com.matuvent.mineturn.network.BattleNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Transparent in-world battle screen. Widgets consume clicks; world drags orbit without issuing actions. */
public final class BattleScreen extends Screen {
    private final List<Button> hotbar = new ArrayList<>();
    private final List<Button> choices = new ArrayList<>();
    private final List<Button> grantButtons=new ArrayList<>();
    private List<BattleNetwork.Offer> displayedGrants=List.of();
    private Button grantPageButton;
    private int grantPage;
    private Button sprint, move, retreat, flee, end, offhand;
    private int slotSize, rowY, rowX;
    private boolean moving;
    private boolean worldPressed;
    private boolean dragged;
    private double pressX, pressY;
    private int chosenSlot = -1;
    private BattleNetwork.ItemAction selectedAction;
    private int selectedAmmoSlot=-1;
    private ItemStack selectedAmmo=ItemStack.EMPTY;
    private Vec3 destination;
    private Vec3 sentDestination;
    private int previewId;
    private int tickCount;
    private int lastPreviewTick = -10;
    private long revision = -1;
    private String localMessage = "";
    private int queueScroll;
    private boolean showRoster;
    private int swimDepth;

    public BattleScreen() { super(Component.literal("MineTurn 战斗")); }
    @Override protected void init() {
        hotbar.clear(); choices.clear(); chosenSlot = -1; selectedAction = null;
        grantButtons.clear();displayedGrants=List.of();grantPageButton=null;
        slotSize = Math.max(22, Math.min(36, (width - 24) / 9 - 4));
        rowX = (width - (slotSize * 9 + 4 * 8)) / 2;
        rowY = height - slotSize - 22;
        for (int slot = 0; slot < 9; slot++) {
            final int index = slot;
            hotbar.add(addRenderableWidget(Button.builder(Component.empty(), button -> selectSlot(index))
                    .bounds(rowX + slot * (slotSize + 4), rowY, slotSize, slotSize + 12).build()));
        }
        int right = width - 104;
        int spacing = height < 280 ? 20 : 25;
        int top = rowY - 4 * spacing - 4;
        offhand=addRenderableWidget(Button.builder(Component.literal("副手举盾"),button->{
            selectSlot(40);
        }).bounds(right,top-2*spacing,92,spacing-3).build());
        sprint = addRenderableWidget(Button.builder(Component.literal("疾跑"), button -> {
            clearModes(); BattleClient.simple("sprint");
        }).bounds(right, top - spacing, 92, spacing - 3).build());
        move = addRenderableWidget(Button.builder(Component.literal("移动"), button -> {
            swimDepth=0;
            clearChoice(); moving = !moving; destination = null; sentDestination = null;
            localMessage = moving ? com.matuvent.mineturn.battle.AquaticPath.inWater(minecraft.level,BattleClient.state.anchor())
                    ? "水中点选移动：E 上浮 / Q 下潜，单击确认；右键取消。" : "选择地面：绿色可到达，单击确认；右键取消。" : "";
        }).bounds(right, top, 92, spacing - 3).build());
        retreat = addRenderableWidget(Button.builder(Component.literal("撤退"), button -> {
            clearModes(); BattleClient.simple("retreat");
        }).bounds(right, top + spacing, 92, spacing - 3).build());
        flee = addRenderableWidget(Button.builder(Component.literal("逃跑"), button -> {
            clearModes(); BattleClient.simple("flee");
        }).bounds(right, top + 2 * spacing, 92, spacing - 3).build());
        end = addRenderableWidget(Button.builder(Component.literal("结束行动"), button -> {
            clearModes(); BattleClient.simple("end");
        }).bounds(right, top + 3 * spacing, 92, spacing - 3).build());
        refresh();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partialTick) { /* Keep the live world visible. */ }
    @Override public void tick() {
        tickCount++;
        refresh();
        if ((moving || potionGround()) && destination != null && BattleClient.active() && tickCount - lastPreviewTick >= 4
                && (sentDestination == null || sentDestination.distanceToSqr(destination) > 1e-6 || tickCount - lastPreviewTick >= 20)) {
            sentDestination = destination; lastPreviewTick = tickCount; previewId++;
            BattleClient.send(potionGround()?"potion_preview":"preview", potionGround()?chosenSlot:0, potionGround()?selectedAction.id():"", -1, destination, previewId);
        }
    }
    private void refresh() {
        if (!BattleClient.active() || move == null) return;
        var state = BattleClient.state;
        if (revision != state.revision()) {
            if (revision != -1) {
                boolean keepMoving = moving && state.canMove();
                clearModes(); moving = keepMoving;
            }
            revision = state.revision(); sentDestination = null;
        }
        boolean ready = !BattleClient.pending && BattleClient.aim == null;
        refreshGrants(ready);
        offhand.active=ready;
        var shieldActions=actions(9);
        offhand.visible=minecraft.player!=null && minecraft.player.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD);
        offhand.setTooltip(Tooltip.create(Component.literal(shieldActions.isEmpty()?"副手盾牌未启用举盾动作。":shieldActions.getFirst().costText()+"\n"+(shieldActions.getFirst().unavailable().isEmpty()?"抵挡下一次行动前的一次攻击；不更换装备。":shieldActions.getFirst().unavailable()))));
        sprint.active = state.canAct() && state.movement() >= 0.01 && ready;
        sprint.setTooltip(Tooltip.create(Component.literal("消耗主要行动，将当前剩余移动距离翻倍；不解除周身范围限制。")));
        move.active = state.canMove() && ready;
        move.setMessage(Component.literal(moving ? "取消移动" : "移动"));
        move.setTooltip(Tooltip.create(Component.literal(state.canMove() ? String.format(Locale.ROOT, "剩余 %.1f 格，可分次移动。进入周身范围后再次移动需要撤退。可上一格台阶，下落会受到原版摔伤。", state.movement())
                : "尚未轮到你、移动距离不足，或需要先撤退。")));
        retreat.active = state.canAct() && state.engaged() && ready;
        retreat.setTooltip(Tooltip.create(Component.literal("消耗主要行动，允许本次移动脱离敌人周身范围。")));
        flee.active = state.canFlee() && ready;
        flee.setTooltip(Tooltip.create(Component.literal("需要主要行动，且距离最近敌人至少 10 格。")));
        end.active = BattleClient.ownTurn() && ready;
        end.setTooltip(Tooltip.create(Component.literal("结束本次行动；30 秒无操作会自动结束，成功行动后重置倒计时。")));
        for (int i = 0; i < hotbar.size(); i++) {
            List<BattleNetwork.ItemAction> actions = actions(i);
            // Keep inspection available even when every action is unavailable; selecting does not submit.
            hotbar.get(i).active = ready;
            ItemStack stack = minecraft.player.getInventory().getItem(i);
            String name = stack.isEmpty() ? "空手" : stack.getHoverName().getString();
            StringBuilder tip = new StringBuilder((i + 1) + " · " + name);
            if (actions.isEmpty()) tip.append(stack.isEmpty()?"\n此空槽位没有战斗动作。":"\n此物品尚未启用战斗动作。可用物品由战斗规则决定。");
            if(!ready)tip.append(BattleClient.aim!=null?"\n请先完成远程判定。":"\n正在等待服务器确认。");
            for (var action : actions) tip.append("\n").append(action.name()).append(" · ").append(action.costText()).append(action.unavailable().isEmpty() ? "" : "：" + action.unavailable());
            hotbar.get(i).setTooltip(Tooltip.create(Component.literal(tip.toString())));
        }
    }
    private List<BattleNetwork.ItemAction> actions(int slot) {
        return BattleClient.active() && slot < BattleClient.state.slots().size() ? BattleClient.state.slots().get(slot).actions() : List.of();
    }
    private void refreshGrants(boolean ready) {
        if(!displayedGrants.equals(BattleClient.offers)) {
            for(var button:grantButtons)removeWidget(button);grantButtons.clear();
            if(grantPageButton!=null){removeWidget(grantPageButton);grantPageButton=null;}
            displayedGrants=BattleClient.offers;
            grantPage=Math.min(grantPage,Math.max(0,(displayedGrants.size()-1)/3));
            if(chosenSlot==-2 && (selectedAction==null || displayedGrants.stream().noneMatch(o->o.id().equals(selectedAction.id()) && o.unavailable().isEmpty())))clearChoice();
            int x=width-204,y=sprint.getY();
            for(int i=grantPage*3;i<Math.min(displayedGrants.size(),grantPage*3+3);i++) {
                var offer=displayedGrants.get(i);
                var button=Button.builder(Component.literal("    "+offer.name()),ignored->{
                    clearModes();chosenSlot=-2;
                    selectAction(new BattleNetwork.ItemAction(offer.id(),offer.name(),offer.self(),offer.allied(),offer.unavailable(),offer.mainCost(),offer.bonusCost()));
                }).bounds(x,y+(i%3)*24,96,22).build();
                button.setTooltip(Tooltip.create(Component.literal(offer.name()+"\n"+offer.costText()+(offer.unavailable().isEmpty()?"":"："+offer.unavailable()))));
                grantButtons.add(addRenderableWidget(button));
            }
            if(displayedGrants.size()>3)grantPageButton=addRenderableWidget(Button.builder(Component.literal("更多行动 "+(grantPage+1)+"/"+((displayedGrants.size()+2)/3)),ignored->{
                grantPage=(grantPage+1)%Math.max(1,(BattleClient.offers.size()+2)/3);displayedGrants=List.of();refreshGrants(!BattleClient.pending && BattleClient.aim==null);
            }).bounds(x,y+72,96,20).build());
        }
        for(int i=0;i<grantButtons.size();i++)grantButtons.get(i).active=ready && displayedGrants.get(grantPage*3+i).unavailable().isEmpty();
    }
    private void clearChoice() {
        for (Button button : choices) removeWidget(button);
        choices.clear(); chosenSlot = -1; selectedAction = null;selectedAmmoSlot=-1;selectedAmmo=ItemStack.EMPTY;
    }
    private void clearModes() {
        clearChoice(); moving = false; destination = null; sentDestination = null; localMessage = "";
    }
    private void selectSlot(int slot) {
        if (!(slot==40?offhand:hotbar.get(slot)).active || BattleClient.pending) return;
        clearModes(); chosenSlot = slot;
        var actions = actions(slot==40?9:slot);
        if(actions.isEmpty()) {
            localMessage=minecraft.player.getInventory().getItem(slot).isEmpty()?"此空槽位没有战斗动作。":"此物品尚未启用战斗动作。";return;
        }
        if (actions.size() == 1) selectAction(actions.getFirst());
        else {
            int columns = Math.max(1, (width - 124) / 94);
            for (int i = 0; i < actions.size(); i++) {
                var action = actions.get(i);
                Button button = Button.builder(Component.literal(action.name()+(action.unavailable().isEmpty()?"":" ×")), ignored -> selectAction(action))
                        .bounds(12 + i % columns * 94, rowY - 27 - i / columns * 24, 90, 20).build();
                button.active = true;
                button.setTooltip(Tooltip.create(Component.literal(action.costText()+(action.unavailable().isEmpty()?"":"\n"+action.unavailable()))));
                choices.add(addRenderableWidget(button));
            }
            localMessage = "选择这个物品的动作。";
        }
    }
    private void selectAction(BattleNetwork.ItemAction action) {
        if (!action.unavailable().isEmpty()) { localMessage = action.name()+"："+action.unavailable()+"（未消耗行动或物品）"; return; }
        selectedAmmoSlot=-1;selectedAmmo=ItemStack.EMPTY;
        if(chosenSlot>=0 && !action.ammo().isEmpty() && action.ammoCount()>0){showAmmo(action,0);return;}
        confirmAction(action);
    }
    private void showAmmo(BattleNetwork.ItemAction action,int page) {
        for(var button:choices)removeWidget(button);choices.clear();selectedAction=null;
        var slots=new ArrayList<Integer>();
        for(int i=0;i<=40;i++) {
            if(i>35 && i!=40 || i==chosenSlot)continue;
            var stack=minecraft.player.getInventory().getItem(i);
            if(!stack.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(action.ammo()))slots.add(i);
        }
        int pages=Math.max(1,(slots.size()+5)/6),current=Math.clamp(page,0,pages-1);
        int cell=Math.max(70,Math.min(150,(width-130)/2)),top=rowY-110;
        for(int i=current*6;i<Math.min(slots.size(),current*6+6);i++) {
            int slot=slots.get(i),index=i-current*6;var snapshot=minecraft.player.getInventory().getItem(slot).copy();
            String label=(slot==40?"副手":"槽"+(slot+1))+" · "+snapshot.getHoverName().getString()+" ×"+snapshot.getCount();
            var button=Button.builder(Component.literal(font.plainSubstrByWidth(label,cell-8)),ignored->{
                selectedAmmoSlot=slot;selectedAmmo=snapshot.copyWithCount(1);confirmAction(action);
            }).bounds(12+index%2*cell,top+index/2*24,cell-4,22).build();
            var tip=Component.literal(label+"\n本次需要 "+action.ammoCount()+" 个\n");
            if(snapshot.getCount()<action.ammoCount())tip.append("该堆叠数量不足，不会自动从其他堆叠补足。\n");
            for(var line:Screen.getTooltipFromItem(minecraft,snapshot))tip.append(line).append("\n");
            button.setTooltip(Tooltip.create(tip));button.active=snapshot.getCount()>=action.ammoCount();
            choices.add(addRenderableWidget(button));
        }
        if(pages>1) {
            var previous=Button.builder(Component.literal("上一页"),ignored->showAmmo(action,current-1)).bounds(12,top+74,cell-4,20).build();
            var next=Button.builder(Component.literal("下一页 "+(current+1)+"/"+pages),ignored->showAmmo(action,current+1)).bounds(12+cell,top+74,cell-4,20).build();
            previous.active=current>0;next.active=current<pages-1;choices.add(addRenderableWidget(previous));choices.add(addRenderableWidget(next));
        }
        localMessage=slots.isEmpty()?"没有匹配的弹药，请重新选择动作。":"选择弹药堆叠；悬停查看药水效果或烟花内容，右键取消。";
    }
    private void sendAction(BattleNetwork.ItemAction action,int target) {
        if(selectedAmmoSlot>=0)BattleClient.useAmmo(chosenSlot,action.id(),target,selectedAmmoSlot,selectedAmmo);
        else BattleClient.send(chosenSlot==-2?"grant":"use",chosenSlot,action.id(),target,Vec3.ZERO,0);
    }
    private void confirmAction(BattleNetwork.ItemAction action) {
        for(var button:choices)removeWidget(button);choices.clear();
        destination=null;sentDestination=null;BattleClient.preview=null;
        if(action.ground()) {
            selectedAction=action;localMessage=teleportGround()?"选择传送落点：战场内可见的地面或水中位置；消耗珍珠和主要行动，并受到 5 点摔落类型伤害。右键取消。":riptideGround()?"选择激流方向与落点：沿预览路线冲刺，接触首个敌人时攻击。水中可调整深度，右键取消。":"选择药水落点：绿色表示可投掷；圆环为范围参考，敌我均可能受影响。单击后进行远程判定，右键取消。";return;
        }
        if (action.self()) {
            sendAction(action,minecraft.player.getId());
            clearModes();
        } else {
            selectedAction = action;
            localMessage = "已选择「" + action.name() + "」"+(selectedAmmoSlot<0?"":" · "+selectedAmmo.getHoverName().getString())+"，单击"+(action.allied()?"友军":"敌人")+"确认目标；右键取消。";
        }
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!BattleClient.active() || minecraft.player == null) return;
        if((riptideGround() || teleportGround()) && !overPanel(mouseX,mouseY))destination=ground(mouseX,mouseY);
        else if(potionGround() && !overPanel(mouseX,mouseY)) {
            Vec3 origin=minecraft.gameRenderer.getMainCamera().getPosition();
            var hit=minecraft.level.clip(new ClipContext(origin,origin.add(direction(mouseX,mouseY).scale(128)),ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,minecraft.player));
            destination=hit.getType()==HitResult.Type.BLOCK && hit.getDirection()==Direction.UP?hit.getLocation():null;
        } else if (moving && !overPanel(mouseX, mouseY)) destination = ground(mouseX, mouseY);
        else destination = null;
        var state = BattleClient.state;
        if(BattleClient.aim!=null) { moving=false; worldPressed=false; }
        renderQueue(graphics);
        int statusX = queueWidth() + 20;
        graphics.fill(statusX - 5, 8, width - 8, 94, 0xCC101922);
        graphics.drawString(font, BattleClient.ownTurn() ? "你的行动" : "等待其他参与者", statusX, 15, BattleClient.ownTurn() ? 0x80EBC0 : 0xF6BF79);
        graphics.drawString(font, String.format(Locale.ROOT, "AV %.2f", state.time()), statusX, 28, 0xCAD5DF);
        graphics.drawString(font, String.format(Locale.ROOT, "生命 %.0f/%.0f", minecraft.player.getHealth(), minecraft.player.getMaxHealth()), statusX, 41, 0xFFFFFF);
        graphics.drawString(font, String.format(Locale.ROOT, "剩余移动 %.1f 格", state.movement()), statusX, 54, 0x80EBC0);
        graphics.drawString(font,"当前回合：主要行动 "+state.mainActions()+" · 次要行动 "+state.bonusActions(),statusX,67,0xCAD5DF);
        graphics.drawString(font,state.turnSeconds()<0?"生物正在行动":"操作剩余 "+state.turnSeconds()+" 秒",statusX,80,state.turnSeconds()>=0 && state.turnSeconds()<=5?0xFF7777:0xF6BF79);
        graphics.fill(rowX - 6, rowY - 6, rowX + slotSize * 9 + 4 * 8 + 6, height - 5, 0xDA101922);
        super.render(graphics, mouseX, mouseY, partialTick);
        for(int i=0;i<grantButtons.size();i++) {
            var offer=displayedGrants.get(grantPage*3+i);var button=grantButtons.get(i);
            var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(offer.icon()));
            graphics.renderItem(new ItemStack(item),button.getX()+2,button.getY()+2);
        }
        for (int i = 0; i < hotbar.size(); i++) {
            Button button = hotbar.get(i);
            int x = button.getX(), y = button.getY();
            ItemStack stack = minecraft.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, x + (slotSize - 16) / 2, y + 5);
                graphics.renderItemDecorations(font, stack, x + (slotSize - 16) / 2, y + 5);
            } else if (!actions(i).isEmpty()) graphics.drawCenteredString(font, "拳", x + slotSize / 2, y + 8, 0xE5EDF3);
            graphics.drawString(font, Integer.toString(i + 1), x + 2, y + 2, 0xA3B4C4, false);
            String label = actions(i).isEmpty() ? "—" : actions(i).size() > 1 ? "动作" : actions(i).getFirst().name();
            boolean usable=button.active && actions(i).stream().anyMatch(action->action.unavailable().isEmpty());
            graphics.drawCenteredString(font, font.plainSubstrByWidth(label, slotSize - 4), x + slotSize / 2, y + slotSize, usable ? 0xD8E7F0 : 0x7A828A);
            if (i == chosenSlot) graphics.renderOutline(x, y, slotSize, slotSize + 12, 0xFF77E1BD);
        }
        String text = BattleClient.pending ? "正在确认…" : localMessage.isEmpty() ? state.message() : localMessage;
        if (moving && destination != null) text = verifiedDestination() ? BattleClient.preview.reason().isEmpty()
                ? "此位置可到达，单击移动；高处下落会受到摔伤。" : BattleClient.preview.reason() : "正在检查落点…";
        if(potionGround() && destination!=null)text=verifiedDestination()?(validDestination()?(teleportGround()?"单击传送：不消耗普通移动距离，会受到珍珠自身伤害。":riptideGround()?"单击激流冲刺：可能提前碰撞停步；高处落地会受到摔伤。":"可投掷：单击进入判定。圆环为范围参考，实际效果受高度、遮挡和距离衰减影响。"):BattleClient.preview.reason()):"正在检查落点…";
        int lineY = 102;
        for (var line : font.split(Component.literal(text), Math.max(60, width - statusX - 16))) {
            if (lineY + 11 > rowY - 130) break;
            graphics.fill(statusX - 5, lineY - 3, width - 8, lineY + 11, 0xB5101922);
            graphics.drawString(font, line, statusX, lineY, 0xF1D795); lineY += 12;
        }
        graphics.drawString(font, "左键拖动旋转 · 滚轮缩放 · Shift+拖动平移 · F聚焦", 12, rowY - 15, 0xB9CAD7);
        if(BattleClient.aim!=null){
            var aim=BattleClient.aim; int bar=Math.min(300,width-40), x=(width-bar)/2, y=Math.max(82,height/2);
            double progress=BattleClient.aimProgress();
            graphics.fill(x-10,y-30,x+bar+10,y+39,0xEF101922);
            graphics.drawCenteredString(font,BattleClient.aimSubmitted ? "正在确认判定…" : progress<0 ? "准备 · 指针即将开始移动" : "指针进入绿色区域时按 空格",width/2,y-21,0xFFFFFF);
            graphics.fill(x,y,x+bar,y+14,0xFF35414D);
            graphics.fill(x+(int)(bar*aim.low()),y,x+(int)(bar*aim.high()),y+14,0xFF36B985);
            int cursor=x+(int)(bar*Math.clamp(progress,0,1));
            graphics.fill(cursor-1,y-4,cursor+2,y+18,0xFFFFDB6A);
            graphics.drawCenteredString(font,"一次判定 · 超时视为未命中",width/2,y+24,0xC9D9E5);
        }
        if(BattleClient.aim!=null){
            var aim=BattleClient.aim; int bar=Math.min(300,width-40), x=(width-bar)/2, y=Math.max(82,height/2);
            double progress=BattleClient.aimProgress();
            graphics.fill(x-10,y-30,x+bar+10,y+39,0xEF101922);
            graphics.drawCenteredString(font,BattleClient.aimSubmitted ? "正在确认判定…" : progress<0 ? "准备 · 指针即将开始移动" : "指针进入绿色区域时按 空格",width/2,y-21,0xFFFFFF);
            graphics.fill(x,y,x+bar,y+14,0xFF35414D);
            graphics.fill(x+(int)(bar*aim.low()),y,x+(int)(bar*aim.high()),y+14,0xFF36B985);
            int cursor=x+(int)(bar*Math.clamp(progress,0,1));
            graphics.fill(cursor-1,y-4,cursor+2,y+18,0xFFFFDB6A);
            graphics.drawCenteredString(font,"一次判定 · 超时视为未命中",width/2,y+24,0xC9D9E5);
        }
    }
    private int queueWidth() { return Math.max(144, Math.min(218, width / 3)); }
    private int queueBottom() { return Math.min(rowY - 25, 286); }
    private int queueRows() { return Math.max(1, (queueBottom() - 70) / 22); }
    private boolean overQueue(double x, double y) { return x >= 8 && x <= queueWidth() + 8 && y >= 8 && y <= queueBottom(); }
    private void renderQueue(GuiGraphics graphics) {
        var state = BattleClient.state;
        int panelWidth = queueWidth();
        graphics.fill(8, 8, panelWidth + 8, queueBottom(), 0xDB101922);
        graphics.drawString(font, "行动队列", 16, 15, 0x80EBC0);
        graphics.drawString(font, "序列", 16, 30, showRoster ? 0x8194A6 : 0xFFFFFF);
        graphics.drawString(font, "全员 " + state.fighters().size(), 61, 30, showRoster ? 0xFFFFFF : 0x8194A6);
        String current = state.fighters().stream().filter(f -> f.id() == state.actorId()).map(BattleNetwork.Fighter::name).findFirst().orElse("—");
        graphics.drawString(font, font.plainSubstrByWidth("当前：" + current, panelWidth - 16), 16, 48, 0xF3D78A);
        int size = showRoster ? state.fighters().size() : state.queue().size();
        queueScroll = Math.clamp(queueScroll, 0, Math.max(0, size - queueRows()));
        for (int i = 0; i < queueRows() && i + queueScroll < size; i++) {
            int index = i + queueScroll, y = 66 + i * 22;
            String name; double av; int color;
            if (showRoster) {
                var fighter = state.fighters().get(index);
                name = fighter.name(); av = fighter.nextAv(); color = fighter.enemy() ? 0xF1A395 : 0x80D9BE;
            } else {
                var entry = state.queue().get(index);
                name = (index + 1) + ". " + entry.name(); av = entry.inAv();
                color = state.fighters().stream().anyMatch(f -> f.id() == entry.id() && f.enemy()) ? 0xF1A395 : 0x80D9BE;
            }
            graphics.drawString(font, font.plainSubstrByWidth(name, panelWidth - 16), 16, y, color);
            graphics.drawString(font, av<0?"机关 · 不行动":String.format(Locale.ROOT, "%.2f AV 后", av), 16, y + 10, 0xB4C5D5, false);
        }
        if (size > queueRows()) graphics.drawString(font, "滚轮查看更多", 16, queueBottom() - 9, 0x8194A6, false);
    }
    private boolean overPanel(double x, double y) {
        if (y >= rowY - 7 || overQueue(x, y) || x >= queueWidth() + 15 && y <= 68) return true;
        for (var child : children()) if (child.isMouseOver(x, y)) return true;
        return false;
    }
    private Vec3 direction(double x, double y) {
        return minecraft.gameRenderer.getMainCamera().getNearPlane().getPointOnPlane((float) (2 * x / width - 1), (float) (1 - 2 * y / height)).normalize();
    }
    private Vec3 ground(double x, double y) {
        Vec3 origin = minecraft.gameRenderer.getMainCamera().getPosition();
        if(com.matuvent.mineturn.battle.AquaticPath.inWater(minecraft.level,BattleClient.state.anchor())){
            Vec3 ray=direction(x,y);
            if(Math.abs(ray.y)<1e-6)return null;
            double distance=(BattleClient.state.anchor().y+swimDepth-origin.y)/ray.y;
            if(distance<=0 || distance>128)return null;
            Vec3 point=origin.add(ray.scale(distance));
            return new Vec3(Math.floor(point.x)+0.5,BattleClient.state.anchor().y+swimDepth,Math.floor(point.z)+0.5);
        }
        BlockHitResult hit = minecraft.level.clip(new ClipContext(origin, origin.add(direction(x, y).scale(1024)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, minecraft.player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP) return null;
        // Server follows actual collision surfaces, including steps and drops.
        return new Vec3(hit.getBlockPos().getX() + 0.5, hit.getLocation().y, hit.getBlockPos().getZ() + 0.5);
    }
    public boolean potionGround(){return selectedAction!=null && selectedAction.ground();}
    public boolean riptideGround(){return potionGround() && selectedAction.self() && !selectedAction.teleport();}
    public boolean teleportGround(){return potionGround() && selectedAction.teleport();}
    public double potionRadius(){return riptideGround() || teleportGround()?0:chosenSlot>=0 && minecraft.player.getInventory().getItem(chosenSlot).is(net.minecraft.world.item.Items.LINGERING_POTION)?3:4;}
    public Vec3 destination() { return moving || potionGround() ? destination : null; }
    public boolean verifiedDestination() {
        var preview = BattleClient.preview;
        return destination != null && preview != null && BattleClient.active() && preview.revision() == BattleClient.state.revision()
                && preview.requestId() == previewId && preview.destination().distanceToSqr(destination) < 1e-6;
    }
    public boolean validDestination() { return verifiedDestination() && BattleClient.preview.valid(); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 1) { clearModes(); return true; }
        if (button == 0 && overQueue(x, y) && y >= 27 && y <= 42) {
            showRoster = x >= 57; queueScroll = 0; return true;
        }
        if (super.mouseClicked(x, y, button)) return true;
        if (button == 0 && !overPanel(x, y)) { worldPressed = true; dragged = false; pressX = x; pressY = y; return true; }
        return false;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && worldPressed) {
            if (Math.hypot(x - pressX, y - pressY) > 4) dragged = true;
            if (dragged) {
                if (hasShiftDown()) {
                    var camera = minecraft.gameRenderer.getMainCamera();
                    Vec3 delta = new Vec3(camera.getLeftVector()).scale(dx * BattleClient.distance * 0.003)
                            .add(new Vec3(camera.getUpVector()).scale(dy * BattleClient.distance * 0.003));
                    Vec3 proposed = BattleClient.focus.add(delta);
                    Vec3 center = BattleClient.state.enemyPosition().add(0, 1, 0);
                    Vec3 offset = proposed.subtract(center);
                    BattleClient.focus = center.add(offset.length() > 12 ? offset.normalize().scale(12) : offset);
                } else {
                    BattleClient.yaw += (float) dx * 0.55f;
                    BattleClient.pitch = Math.clamp(BattleClient.pitch + (float) dy * 0.55f, 8, 85);
                }
            }
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && worldPressed) {
            worldPressed = false;
            if (!dragged && !overPanel(x, y) && !BattleClient.pending) worldClick(x, y);
            return true;
        }
        return super.mouseReleased(x, y, button);
    }
    private void worldClick(double x, double y) {
        if(potionGround()) {
            if(validDestination()){BattleClient.send("potion_ground",chosenSlot,selectedAction.id(),-1,destination,0);clearModes();}
            else localMessage="请选择通过服务端检查的地面落点。";
            return;
        }
        if (moving) {
            destination = ground(x, y);
            if (destination != null) BattleClient.send("move", 0, "", -1, destination, previewId);
            else localMessage = destination == null ? "请选择有支撑的地面。" : "这个落点尚未通过检查。";
        } else if (selectedAction != null) {
            Vec3 origin = minecraft.gameRenderer.getMainCamera().getPosition();
            Vec3 rayEnd = origin.add(direction(x, y).scale(64));
            var block = minecraft.level.clip(new ClipContext(origin, rayEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
            Entity enemy = null;
            double closest = block.getType() == HitResult.Type.MISS ? 4096 : block.getLocation().distanceToSqr(origin);
            for (var fighter : BattleClient.state.fighters()) {
                if (fighter.enemy()==selectedAction.allied() || fighter.health() <= 0) continue;
                Entity candidate = minecraft.level.getEntity(fighter.id());
                if (candidate == null) continue;
                var targetBox=candidate.getBoundingBox();
                if(candidate.getVehicle()!=null)targetBox=targetBox.minmax(candidate.getVehicle().getBoundingBox());
                var hit = targetBox.inflate(0.12).clip(origin, rayEnd);
                if (hit.isPresent() && hit.get().distanceToSqr(origin) <= closest) {
                    closest = hit.get().distanceToSqr(origin); enemy = candidate;
                }
            }
            if (enemy != null) {
                sendAction(selectedAction,enemy.getId());
                clearModes();
            } else localMessage = "请单击可见的"+(selectedAction.allied()?"友军":"敌人")+"以确认目标。";
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (overQueue(x, y)) { queueScroll = Math.max(0, queueScroll - (int) Math.signum(vertical)); return true; }
        BattleClient.distance = Math.clamp(BattleClient.distance * Math.pow(0.88, vertical), 2.5, 24);
        return true;
    }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if(BattleClient.aim!=null && key==GLFW.GLFW_KEY_SPACE){BattleClient.judgeAim();return true;}
        if((moving || riptideGround() || teleportGround()) && (key==GLFW.GLFW_KEY_Q||key==GLFW.GLFW_KEY_E) && com.matuvent.mineturn.battle.AquaticPath.inWater(minecraft.level,BattleClient.state.anchor())){
            int limit=riptideGround() || teleportGround()?16:(int)BattleClient.state.movement();
            swimDepth=Math.clamp(swimDepth+(key==GLFW.GLFW_KEY_E?1:-1),-limit,limit);
            sentDestination=null;
            localMessage="水中点选移动：E 上浮 / Q 下潜；目标高度差 "+swimDepth+" 格。";
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (moving || chosenSlot >= 0) clearModes();
            else minecraft.setScreen(new PauseScreen(true));
            return true;
        }
        if (key == GLFW.GLFW_KEY_F) { BattleClient.focus = BattleClient.state.anchor().add(0, 1, 0); return true; }
        if (key == GLFW.GLFW_KEY_T || key == GLFW.GLFW_KEY_SLASH) { minecraft.setScreen(new ChatScreen(key == GLFW.GLFW_KEY_SLASH ? "/" : "")); return true; }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) { selectSlot(key - GLFW.GLFW_KEY_1); return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }
}
