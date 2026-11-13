package dev.aether.mixin

import dev.aether.proxy.AetherProxy
import dev.aether.proxy.AetherProxyManager
import io.netty.channel.Channel
import io.netty.handler.proxy.Socks4ProxyHandler
import io.netty.handler.proxy.Socks5ProxyHandler
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(targets = ["net.minecraft.network.Connection\$1"])
class MixinConnectionChannelInitializer {
    @Inject(method = ["initChannel(Lio/netty/channel/Channel;)V"], at = [At("HEAD")])
    private fun `aether$addProxyHandler`(channel: Channel, ci: CallbackInfo) {
        val proxy = AetherProxyManager.selectedProxy()
        if (proxy == null) {
            AetherProxyManager.markConnectionProxy(null)
            return
        }

        if (proxy.type() == AetherProxy.ProxyType.SOCKS5) {
            channel.pipeline().addFirst(Socks5ProxyHandler(
                proxy.socketAddress(),
                if (proxy.username().isEmpty()) null else proxy.username(),
                if (proxy.password().isEmpty()) null else proxy.password()
            ))
        } else {
            channel.pipeline().addFirst(Socks4ProxyHandler(
                proxy.socketAddress(),
                if (proxy.username().isEmpty()) null else proxy.username()
            ))
        }
        AetherProxyManager.markConnectionProxy(proxy)
    }
}
