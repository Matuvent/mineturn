execute store result score @s mineturn.ai run mineturn ai target nearest_enemy
execute if score @s mineturn.ai matches 0 run return run mineturn ai wait
execute store result score @s mineturn.ai run mineturn ai query ready mineturn:pillager_charge
execute if score @s mineturn.ai matches 1 run return run mineturn ai use mineturn:pillager_charge
execute unless items entity @s weapon.mainhand minecraft:crossbow unless items entity @s weapon.offhand minecraft:crossbow run return run function mineturn:ai/animated_melee/turn {action:"mineturn:mob_melee"}
execute store result score @s mineturn.ai run mineturn ai query in_attack_range
execute if score @s mineturn.ai matches 1 run return run mineturn ai wait
execute store result score @s mineturn.ai run mineturn ai query engaged
execute if score @s mineturn.ai matches 1 run mineturn ai retreat
return run mineturn ai move_async toward_target
