package dev.duelevent;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import static org.mockito.Mockito.*;
public final class DuelIntegrationTest {
    static int checks;
    static void check(boolean ok,String msg) {if(!ok) throw new AssertionError(msg);checks++;}
    static Player player() { Player p=mock(Player.class); when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.getName()).thenReturn("Test");return p; }
    public static void main(String[] args) throws Exception {
        DuelPlugin plugin=mock(DuelPlugin.class,CALLS_REAL_METHODS); DuelManager manager=mock(DuelManager.class);
        var field=DuelPlugin.class.getDeclaredField("duels");field.setAccessible(true);field.set(plugin,manager);
        var config=new YamlConfiguration();doReturn(config).when(plugin).getConfig();
        Player a=player(),b=player(),friend=player(),outsider=player();
        UUID aid=a.getUniqueId(),bid=b.getUniqueId(),fid=friend.getUniqueId(),oid=outsider.getUniqueId();
        Duel duel=new Duel(null,null,new Team(List.of(a,friend)),new Team(List.of(b)),true);
        when(manager.duelOf(aid)).thenReturn(duel);when(manager.duelOf(bid)).thenReturn(duel);when(manager.duelOf(fid)).thenReturn(duel);
        check(plugin.isDuelParticipant(aid),"Countdown participant recognized");
        check(!plugin.allowsDuelCombat(aid,bid),"No countdown combat exemption");
        duel.phase=Duel.Phase.FIGHTING;
        check(plugin.allowsDuelCombat(aid,bid),"Active opponents exempt from PvE rule");
        check(!plugin.allowsDuelCombat(aid,oid),"Outsiders not exempt");
        check(!plugin.allowsDuelCombat(aid,fid),"Friendly fire setting respected");
        config.set("rules.friendly-fire",true);check(plugin.allowsDuelCombat(aid,fid),"Explicit friendly fire honored");
        duel.team2.alive.remove(bid);check(!plugin.allowsDuelCombat(aid,bid)&&plugin.isDuelParticipant(bid),"Spectators cannot fight but remain duel inventory owners");
        duel.team2.alive.add(bid);duel.phase=Duel.Phase.ENDING;
        check(!plugin.allowsDuelCombat(aid,bid)&&plugin.isDuelParticipant(aid),"Ending restoration remains grave-free");
        when(manager.duelOf(aid)).thenReturn(null);check(!plugin.isDuelParticipant(aid),"Normal survival resumes after leaving");
        System.out.println("Duel integration checks passed: "+checks);
    }
}
