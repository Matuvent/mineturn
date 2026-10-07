execute store result score @s mineturn.ai run mineturn ai query ready mineturn:silverfish_summon
execute if score @s mineturn.ai matches 1 run return run mineturn ai use mineturn:silverfish_summon
return run function mineturn:ai/animated_melee/turn {action:"mineturn:small_bite"}
