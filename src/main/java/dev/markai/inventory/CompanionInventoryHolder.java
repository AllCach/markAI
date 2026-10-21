package dev.markai.inventory;

/** Inventory and synchronized identity supplied to wolves by the companion mixin. */
public interface CompanionInventoryHolder {
    boolean markai$isCompanion();
    void markai$setCompanion(boolean companion);
    DogInventory markai$inventory();
}
