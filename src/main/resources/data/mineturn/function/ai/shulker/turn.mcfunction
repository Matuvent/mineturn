execute store result score @s mineturn.ai run mineturn ai target nearest_enemy
execute if score @s mineturn.ai matches 0 run return run mineturn ai wait
execute store result score @s mineturn.ai run mineturn ai query ready mineturn:shulker_shot
execute if score @s mineturn.ai matches 1 run return run mineturn ai use mineturn:shulker_shot
mineturn ai wait
