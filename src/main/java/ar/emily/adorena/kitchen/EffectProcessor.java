package ar.emily.adorena.kitchen;

import ar.emily.adorena.caffeine.BukkitScheduler;
import ar.emily.adorena.config.BasicEffectSettings;
import ar.emily.adorena.config.EffectKind;
import ar.emily.adorena.config.ReloadableConfiguration;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.papermc.paper.util.Tick;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.UUID;

/** Class responsible for applying and tracking applied effects with a configured cooldown */
@SuppressWarnings("UnstableApiUsage")
public final class EffectProcessor {

  private static final NamespacedKey GROWTH_MODIFIER_KEY = new NamespacedKey("adorena", "growth");
  private static final NamespacedKey COUNTER_PDC_KEY = new NamespacedKey("adorena", "counter");

  private static AttributeModifier createModifier(final double amount) {
    return new AttributeModifier(
        GROWTH_MODIFIER_KEY, amount, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY
    );
  }

  private static CooldownCaches buildCooldownSets(final ReloadableConfiguration config) {
    final long killCooldownTicks = config.effectOnKill().cooldownTicks();
    final Cache<UUID, Unit> killCooldownCache =
        Caffeine.newBuilder()
            .expireAfterWrite(Tick.of(killCooldownTicks))
            .scheduler(BukkitScheduler.INSTANCE)
            .build();
    final long deathCooldownTicks = config.effectOnDeath().cooldownTicks();
    final Cache<UUID, Unit> deathCooldownCache =
        Caffeine.newBuilder()
            .expireAfterWrite(Tick.of(deathCooldownTicks))
            .scheduler(BukkitScheduler.INSTANCE)
            .build();
    return new CooldownCaches(killCooldownCache, deathCooldownCache);
  }

  private final ReloadableConfiguration config;
  private CooldownCaches cooldownCaches;

  public EffectProcessor(final ReloadableConfiguration config) {
    this.config = config;

    final var emptyCache = Caffeine.newBuilder().<UUID, Unit>build();
    this.cooldownCaches = new CooldownCaches(emptyCache, emptyCache);
    this.config.attachReloadListener(this::rebuildCooldownSets);
  }

  public void loadEffects(final LivingEntity target) {
    final int amplitude = getEffectsAmplitude(target);
    resetEffects(target); // remove potentially old/persistent modifiers
    if (amplitude != 0) {
      setEffectsAmplitude(target, amplitude);
    }
  }

  public void resetEffects(final LivingEntity target) {
    target.getPersistentDataContainer().remove(COUNTER_PDC_KEY);
    for (final Attribute attribute : Registry.ATTRIBUTE) {
      final AttributeInstance attributeInstance = target.getAttribute(attribute);
      if (attributeInstance != null) {
        attributeInstance.removeModifier(GROWTH_MODIFIER_KEY);
      }
    }
  }

  public int getEffectsAmplitude(final LivingEntity target) {
    return target.getPersistentDataContainer().getOrDefault(COUNTER_PDC_KEY, PersistentDataType.INTEGER, 0);
  }

  public void setEffectsAmplitude(final LivingEntity target, int amplitude) {
    amplitude = Math.clamp(amplitude, -this.config.effectOnDeath().maximumTimes(), this.config.effectOnKill().maximumTimes());

    target.getPersistentDataContainer().set(COUNTER_PDC_KEY, PersistentDataType.INTEGER, amplitude);

    final BasicEffectSettings currentEffect = amplitude < 0 ? this.config.effectOnDeath() : this.config.effectOnKill();
    final double scalar = scalarFromRate(this.config.growthRate(), currentEffect.effect());
    final double scale = scalar * Math.abs(amplitude);

    this.config.attributeScaleMultipliers().forEach((attributeKey, scaleAdjuster) -> {
      final Attribute attribute = Objects.requireNonNull(Registry.ATTRIBUTE.get(attributeKey));
      final AttributeInstance attributeInstance = target.getAttribute(attribute);
      if (attributeInstance != null) {
        final double adjustedScale = scaleAdjuster * scale;
        final AttributeModifier modifier = attributeInstance.getModifier(GROWTH_MODIFIER_KEY);
        if (modifier != null) { attributeInstance.removeModifier(modifier); }
        attributeInstance.addTransientModifier(createModifier(adjustedScale));
      }
    });
  }

  /**
   * @author rymiel
   */
  public void applyEffects(final LivingEntity target, final ApplicationCause cause) {
    if (this.cooldownCaches.set(target, cause)) {
      return;
    }

    final PersistentDataContainer pdc = target.getPersistentDataContainer();
    int counter = pdc.getOrDefault(COUNTER_PDC_KEY, PersistentDataType.INTEGER, 0);

    if (cause == ApplicationCause.KILL) {
      counter = Math.min(counter + 1, this.config.effectOnKill().maximumTimes());
    } else if (
        cause == ApplicationCause.DEATH_PVP && this.config.effectOnKill().resetOnPvpDeath()
        || cause == ApplicationCause.DEATH_PVE && this.config.effectOnKill().resetOnPveDeath()
    ) {
      counter = 0;
    } else {
      counter = Math.max(counter - 1, -this.config.effectOnDeath().maximumTimes());
    }

    setEffectsAmplitude(target, counter);
  }

  private static double scalarFromRate(final double rate, final EffectKind kind) {
    return switch (kind) {
      case GROW -> rate;
      case SHRINK -> -rate;
      case NONE -> 0;
    };
  }

  private void rebuildCooldownSets() {
    final CooldownCaches newCooldownCaches = buildCooldownSets(this.config);
    newCooldownCaches.killCooldownCache.putAll(this.cooldownCaches.killCooldownCache.asMap());
    newCooldownCaches.deathCooldownCache.putAll(this.cooldownCaches.deathCooldownCache.asMap());
    this.cooldownCaches.killCooldownCache.invalidateAll();
    this.cooldownCaches.deathCooldownCache.invalidateAll();

    this.cooldownCaches = newCooldownCaches;
  }

  public enum ApplicationCause {
    DEATH_PVP, DEATH_PVE, KILL;

    public static ApplicationCause deathForKiller(final LivingEntity killer) {
      return killer instanceof Player ? DEATH_PVP : DEATH_PVE;
    }
  }

  private record CooldownCaches(Cache<UUID, Unit> killCooldownCache, Cache<UUID, Unit> deathCooldownCache) {

    Cache<UUID, Unit> cacheFor(final ApplicationCause cause) {
      return cause == ApplicationCause.KILL ? this.killCooldownCache : this.deathCooldownCache;
    }

    boolean set(final LivingEntity target, final ApplicationCause cause) {
      return cacheFor(cause).asMap().putIfAbsent(target.getUniqueId(), Unit.INSTANCE) != null;
    }
  }

  private enum Unit {
    INSTANCE
  }
}
