package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;

final class RankProgress {
    private RankProgress() {}
    static List<Component> messages(PlayerRecord record,Settings settings) {
        List<Component> lines=new ArrayList<>();
        lines.add(Component.text("Rank: ",NamedTextColor.AQUA).append(settings.rank(record).label()));
        lines.add(Component.text(String.format(Locale.ROOT,"Playtime: %.2f hours",record.playMillis()/3_600_000.0),NamedTextColor.AQUA));
        if(record.manualRank()!=null)lines.add(Component.text("Manual rank assigned; automatic promotion is paused.",NamedTextColor.YELLOW));
        if(record.ogEarned())lines.add(Component.text("OG Player earned — 100% complete.",NamedTextColor.GOLD));
        else if(record.manualRank()==null) {
            double progress=Math.min(1,record.playMillis()/(double)settings.promotionMillis());
            int filled=(int)Math.floor(progress*10);
            lines.add(Component.text("OG progress: ",NamedTextColor.AQUA).append(Component.text("█".repeat(filled),NamedTextColor.GOLD))
                    .append(Component.text("░".repeat(10-filled),NamedTextColor.DARK_GRAY)).append(Component.text(String.format(Locale.ROOT," %.1f%%",progress*100),NamedTextColor.WHITE)));
            long remaining=Math.max(0,settings.promotionMillis()-record.playMillis());
            lines.add(Component.text("Time until OG Player: "+Values.duration(((remaining+999)/1000)*1000),NamedTextColor.AQUA));
            if(remaining==0)lines.add(Component.text("Ready for OG Player; promotion will apply shortly.",NamedTextColor.GOLD));
        }
        return List.copyOf(lines);
    }
}
