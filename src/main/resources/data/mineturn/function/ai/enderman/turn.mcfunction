execute store result score @s mineturn.ai run mineturn ai target nearest_enemy
execute if score @s mineturn.ai matches 0 run return run mineturn ai wait
execute store result score @s mineturn.ai run mineturn ai query in_attack_range
execute unless score @s mineturn.ai matches 1 run function mineturn:ai/enderman/teleport
return run function mineturn:ai/animated_melee/turn {action:"mineturn:mob_melee"}
