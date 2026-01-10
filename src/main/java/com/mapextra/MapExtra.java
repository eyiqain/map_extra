package com.mapextra;


//import com.mapextra.client.particles.ModParticles;
import com.mapextra.init.ModItemRegister;
import com.mapextra.init.ModSounds;
import com.mapextra.net.ModMessage;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(MapExtra.MODID)
public class MapExtra {

    public static final String MODID = "map_extra";
    private static final Logger LOGGER = LogUtils.getLogger();

    public MapExtra() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModMessage.register();
        InitAll(bus);//注册物品，声音
  //      config();//注册配置
        //注册例子
//        ModParticles.PARTICLE_TYPES.register(bus);
    }
    public void InitAll(IEventBus iEventBus){//正常注册进世界总线
        ModItemRegister.init(iEventBus);
        ModSounds.register(FMLJavaModLoadingContext.get().getModEventBus());
    }
//    private void config() {
//        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.COMMON_CONFIG);
//    }
}
