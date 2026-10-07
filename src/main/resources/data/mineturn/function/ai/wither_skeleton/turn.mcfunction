execute if items entity @s weapon.mainhand minecraft:bow run return run function mineturn:ai/skeleton/turn
execute if items entity @s weapon.offhand minecraft:bow run return run function mineturn:ai/skeleton/turn
return run function mineturn:ai/animated_melee/turn {action:"mineturn:species_melee"}
