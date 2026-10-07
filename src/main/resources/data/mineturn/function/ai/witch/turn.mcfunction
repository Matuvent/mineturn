execute store result score @s mineturn.ai run mineturn ai query health_ratio
execute if score @s mineturn.ai matches ..350 store result score @s mineturn.ai run mineturn ai query ready mineturn:witch_heal
execute if score @s mineturn.ai matches 1 run return run mineturn ai use mineturn:witch_heal
execute store result score @s mineturn.ai run mineturn ai target nearest_enemy
execute if score @s mineturn.ai matches 0 run return run mineturn ai wait
execute store result score @s mineturn.ai run mineturn ai query distance
execute if score @s mineturn.ai matches 6000.. run function mineturn:ai/witch/slow
execute store result score @s mineturn.ai run mineturn ai query can_act
execute if score @s mineturn.ai matches 0 run return 0
execute store result score @s mineturn.ai run random value 1..2
execute if score @s mineturn.ai matches 1 store result score @s mineturn.ai run mineturn ai query ready mineturn:witch_poison
execute if score @s mineturn.ai matches 1 run return run mineturn ai use mineturn:witch_poison
return run function mineturn:ai/animated_melee/turn {action:"mineturn:witch_harming"}
